/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.sqoop.manager;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.lang.reflect.Method;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.lib.db.OracleDataDrivenDBInputFormat;
import org.apache.hadoop.sqoop.SqoopOptions;
import org.apache.hadoop.sqoop.util.ImportException;

/**
 * Manages connections to Oracle databases.
 * Requires the Oracle JDBC driver.
 */
public class OracleManager extends GenericJdbcManager {

  public static final Log LOG = LogFactory.getLog(OracleManager.class.getName());

  // driver class to ensure is loaded when making db connection.
  private static final String DRIVER_CLASS = "oracle.jdbc.OracleDriver";


  // Oracle XE does a poor job of releasing server-side resources for
  // closed connections. So we actually want to cache connections as
  // much as possible. This is especially important for JUnit tests which
  // may need to make 60 or more connections (serially), since each test
  // uses a different OracleManager instance.
  private static class ConnCache {

    public static final Log LOG = LogFactory.getLog(ConnCache.class.getName());

    private static class CacheKey {
      public final String connectString;
      public final String username;

      public CacheKey(String connect, String user) {
        this.connectString = connect;
        this.username = user; // note: may be null.
      }

      @Override
      public boolean equals(Object o) {
        if (o instanceof CacheKey) {
          CacheKey k = (CacheKey) o;
          if (null == username) {
            return k.username == null && k.connectString.equals(connectString);
          } else {
            return k.username.equals(username)
                && k.connectString.equals(connectString);
          }
        } else {
          return false;
        }
      }

      @Override
      public int hashCode() {
        if (null == username) {
          return connectString.hashCode();
        } else {
          return username.hashCode() ^ connectString.hashCode();
        }
      }

      @Override
      public String toString() {
        return connectString + "/" + username;
      }
    }

    private Map<CacheKey, Connection> connectionMap;

    public ConnCache() {
      LOG.debug("Instantiated new connection cache.");
      connectionMap = new HashMap<CacheKey, Connection>();
    }

    /**
     * @return a Connection instance that can be used to connect to the
     * given database, if a previously-opened connection is available in
     * the cache. Returns null if none is available in the map.
     */
    public synchronized Connection getConnection(String connectStr,
        String username) throws SQLException {
      CacheKey key = new CacheKey(connectStr, username);
      Connection cached = connectionMap.get(key);
      if (null != cached) {
        connectionMap.remove(key);
        if (cached.isReadOnly()) {
          // Read-only mode? Don't want it.
          cached.close();
        }

        if (cached.isClosed()) {
          // This connection isn't usable.
          return null;
        }

        cached.rollback(); // Reset any transaction state.
        cached.clearWarnings();

        LOG.debug("Got cached connection for " + key);
      }

      return cached;
    }

    /**
     * Returns a connection to the cache pool for future use. If a connection
     * is already cached for the connectstring/username pair, then this
     * connection is closed and discarded.
     */
    public synchronized void recycle(String connectStr, String username,
        Connection conn) throws SQLException {

      CacheKey key = new CacheKey(connectStr, username);
      Connection existing = connectionMap.get(key);
      if (null != existing) {
        // Cache is already full for this entry.
        LOG.debug("Discarding additional connection for " + key);
        conn.close();
        return;
      }

      // Put it in the map for later use.
      LOG.debug("Caching released connection for " + key);
      connectionMap.put(key, conn);
    }

    @Override
    protected synchronized void finalize() throws Throwable {
      for (Connection c : connectionMap.values()) {
        c.close();
      }
      
      super.finalize();
    }
  }

  private static final ConnCache CACHE;
  static {
    CACHE = new ConnCache();
  }

  public OracleManager(final SqoopOptions opts) {
    super(DRIVER_CLASS, opts);
  }

  public void close() throws SQLException {
    release(); // Release any open statements associated with the connection.
    if (hasOpenConnection()) {
      // Release our open connection back to the cache.
      CACHE.recycle(options.getConnectString(), options.getUsername(),
          getConnection());
    }
  }

  protected String getColNamesQuery(String tableName) {
    // SqlManager uses "tableName AS t" which doesn't work in Oracle.
    return "SELECT t.* FROM " + escapeTableName(tableName) + " t";
  }

  /**
   * Create a connection to the database; usually used only from within
   * getConnection(), which enforces a singleton guarantee around the
   * Connection object.
   *
   * Oracle-specific driver uses READ_COMMITTED which is the weakest
   * semantics Oracle supports.
   */
  protected Connection makeConnection() throws SQLException {

    Connection connection;
    String driverClass = getDriverClass();

    try {
      Class.forName(driverClass);
    } catch (ClassNotFoundException cnfe) {
      throw new RuntimeException("Could not load db driver class: " + driverClass);
    }

    String username = options.getUsername();
    String password = options.getPassword();
    String connectStr = options.getConnectString();

    connection = CACHE.getConnection(connectStr, username);
    if (null == connection) {
      // Couldn't pull one from the cache. Get a new one.
      LOG.debug("Creating a new connection for "
          + connectStr + "/" + username);
      if (null == username) {
        connection = DriverManager.getConnection(connectStr);
      } else {
        connection = DriverManager.getConnection(connectStr, username,
            password);
      }
    }

    // We only use this for metadata queries. Loosest semantics are okay.
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

    // Setting session time zone
    setSessionTimeZone(connection);

    return connection;
  }

  /**
   * Set session time zone
   * @param conn      Connection object
   * @throws          SQLException instance
   */
  private void setSessionTimeZone(Connection conn) throws SQLException {
    // need to use reflection to call the method setSessionTimeZone on the OracleConnection class
    // because oracle specific java libraries are not accessible in this context
    Method method;
    try {
      method = conn.getClass().getMethod(
              "setSessionTimeZone", new Class [] {String.class});
    } catch (Exception ex) {
      LOG.error("Could not find method setSessionTimeZone in " + conn.getClass().getName(), ex);
      // rethrow SQLException
      throw new SQLException(ex);
    }

    // Need to set the time zone in order for Java
    // to correctly access the column "TIMESTAMP WITH LOCAL TIME ZONE"
    String clientTimeZone = TimeZone.getDefault().getID();
    try {
      method.setAccessible(true);
      method.invoke(conn, clientTimeZone);
      LOG.info("Time zone has been set");
    } catch (Exception ex) {
      LOG.warn("Time zone " + clientTimeZone +
               " could not be set on oracle database.");
      LOG.info("Setting default time zone: UTC");
      try {
        method.invoke(conn, "UTC");
      } catch (Exception ex2) {
        LOG.error("Could not set time zone for oracle connection", ex2);
        // rethrow SQLException
        throw new SQLException(ex);
      }
    }
  }

  public void importTable(ImportJobContext context)
      throws IOException, ImportException {
    // Specify the Oracle-specific DBInputFormat for import.
    context.setInputFormat(OracleDataDrivenDBInputFormat.class);
    super.importTable(context);
  }

  @Override
  public ResultSet readTable(String tableName, String[] columns) throws SQLException {
    if (columns == null) {
      columns = getColumnNames(tableName);
    }

    StringBuilder sb = new StringBuilder();
    sb.append("SELECT ");
    boolean first = true;
    for (String col : columns) {
      if (!first) {
        sb.append(", ");
      }
      sb.append(escapeColName(col));
      first = false;
    }
    sb.append(" FROM ");
    sb.append(escapeTableName(tableName));

    String sqlCmd = sb.toString();
    LOG.debug("Reading table with command: " + sqlCmd);
    return execute(sqlCmd);
  }

  /**
   * Resolve a database-specific type to the Java type that should contain it.
   * @param sqlType
   * @return the name of a Java type to hold the sql datatype, or null if none.
   */
  public String toJavaType(int sqlType) {
    String defaultJavaType = super.toJavaType(sqlType);
    return (defaultJavaType == null) ? dbToJavaType(sqlType) : defaultJavaType;
  }

  /**
   * Attempt to map sql type to java type
   * @param sqlType     sql type
   * @return            java type
   */
  private String dbToJavaType(int sqlType) {
    // load class oracle.jdbc.OracleTypes
    // need to use reflection because oracle specific libraries
    // are not accessible in this context
    Class typeClass = getTypeClass("oracle.jdbc.OracleTypes");

    // check if it is TIMESTAMPTZ
    int dbType = getDatabaseType(typeClass, "TIMESTAMPTZ");
    if (sqlType == dbType) {
      return "java.sql.Timestamp";
    }

    // check if it is TIMESTAMPLTZ
    dbType = getDatabaseType(typeClass, "TIMESTAMPLTZ");
    if (sqlType == dbType) {
      return "java.sql.Timestamp";
    }

    // return null if no java type was found for sqlType
    return null;
  }
    
  /**
   * Attempt to map sql type to hive type
   * @param sqlType     sql data type
   * @return            hive data type
   */
  public String toHiveType(int sqlType) {
    String defaultHiveType = super.toHiveType(sqlType);
    return (defaultHiveType == null) ? dbToHiveType(sqlType) : defaultHiveType;
  }

  /**
   * Resolve a database-specific type to Hive type
   * @param sqlType     sql type
   * @return            hive type
   */
  private String dbToHiveType(int sqlType) {
    // load class oracle.jdbc.OracleTypes
    // need to use reflection because oracle specific libraries
    // are not accessible in this context
    Class typeClass = getTypeClass("oracle.jdbc.OracleTypes");

    // check if it is TIMESTAMPTZ
    int dbType = getDatabaseType(typeClass, "TIMESTAMPTZ");
    if (sqlType == dbType) {
      return "STRING";
    }

    // check if it is TIMESTAMPLTZ
    dbType = getDatabaseType(typeClass, "TIMESTAMPLTZ");
    if (sqlType == dbType) {
      return "STRING";
    }

    // return null if no hive type was found for sqlType
    return null;
  }

  /**
   * Get database type
   * @param clazz         oracle class representing sql types
   * @param fieldName     field name
   * @return              value of database type constant
   */
  private int getDatabaseType(Class clazz, String fieldName) {
    // need to use reflection to extract constant values
    // because the database specific java libraries are not accessible in this context
    int value = -1;
    try {
      java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
      value = field.getInt(null);
    } catch (NoSuchFieldException ex) {
      LOG.error("Could not retrieve value for field " + fieldName, ex);
    } catch (IllegalAccessException ex) {
      LOG.error("Could not retrieve value for field " + fieldName, ex);
    }
    return value;
  }

  /**
   * Load class by name
   * @param className     class name
   * @return              class instance
   */
  private Class getTypeClass(String className) {
    // need to use reflection to load class
    // because the database specific java libraries are not accessible in this context
    Class typeClass = null;
    try {
      typeClass = Class.forName(className);
    } catch (ClassNotFoundException ex) {
      LOG.error("Could not load class " + className, ex);
    }
    return typeClass;
  }

  @Override
  protected void finalize() throws Throwable {
    close();
    super.finalize();
  }
}

