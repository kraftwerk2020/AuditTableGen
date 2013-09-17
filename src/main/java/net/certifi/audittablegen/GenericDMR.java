/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package net.certifi.audittablegen;

import com.google.common.base.Throwables;
import java.sql.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import javax.sql.DataSource;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.resource.FileSystemResourceAccessor;
import org.apache.commons.collections.map.CaseInsensitiveMap;
import org.apache.commons.dbcp.BasicDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * @author Glenn Sacks
 */
class GenericDMR implements DataSourceDMR {
    private static final Logger logger = LoggerFactory.getLogger(GenericDMR.class);
    
    DataSource dataSource;
    String databaseProduct;
    String unverifiedSchema;
    String verifiedSchema;
    String unverifiedAuditConfigTable = "auditconfig";
    String verifiedAuditConfigTable;
    Queue<List<DBChangeUnit>> operations = new ArrayDeque<>();
    //IdentifierMetaData idMetaData;
   
    
    /**
     * 
     * @param ds A DataSource. Unless set elsewhere,
     * the default database/schema will be targeted.
     * 
     * @throws SQLException 
     */
    GenericDMR (DataSource ds) throws SQLException{
        
        this (ds, null);
        
    }
    /**
     *
     * @param ds A DataSource
     *
     * @param schema Name of schema to perform operations upon.
     * @throws SQLException
     */
    GenericDMR(DataSource ds, String schema) throws SQLException {

        dataSource = ds;
        Connection conn = ds.getConnection();
        DatabaseMetaData dmd = conn.getMetaData();
        databaseProduct = dmd.getDatabaseProductName();
        //idMetaData = new IdentifierMetaData();

        //storing this data for potential future use.
        //not using it for anything currently
        //idMetaData.setStoresLowerCaseIds(dmd.storesLowerCaseIdentifiers());
        //idMetaData.setStoresMixedCaseIds(dmd.storesMixedCaseIdentifiers());
        //idMetaData.setStoresUpperCaseIds(dmd.storesUpperCaseIdentifiers());

        unverifiedSchema = schema;

        conn.close();

    }
    
    /**
     * Generate a DataSource from Properties 
     * @param props
     * @return BasicDataSource as DataSource
     */
    static DataSource getRunTimeDataSource(Properties props){
        
        BasicDataSource dataSource = new BasicDataSource();
        
        dataSource.setDriverClassName(props.getProperty("driver", ""));
        dataSource.setUsername(props.getProperty("username"));
        dataSource.setPassword(props.getProperty("password"));
        dataSource.setUrl(props.getProperty("url"));
        dataSource.setMaxActive(10);
        dataSource.setMaxIdle(5);
        dataSource.setInitialSize(5);
        
        //dataSource.setValidationQuery("SELECT 1");
        
        return dataSource;
    }
    
    /**
     * Return true of the audit configuration source is
     * avaliable.  Only one source is currently supported, and
     * that is a table in the target database/schema named
     * auditconfig.
     * 
     * @return 
     */
    @Override
    public Boolean hasAuditConfigTable (){
        
        return ( (getAuditConfigTableName() != null) ? true : false );
        
    }
   
    
    @Override
    public void createAuditConfigTable() {
        
        try {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(dataSource.getConnection()));
            String schema = getSchema();
            if (null != schema) {
                database.setDefaultSchemaName(schema);
            }
            Liquibase liquibase = new Liquibase("src/main/resources/changesets/changeset-init-config.xml", new FileSystemResourceAccessor(), database);
            liquibase.update(null);
            database.close();
        } catch (SQLException ex) {
            logger.error("Error genereating audit configuration tables", ex);
        } catch (DatabaseException ex) {
            logger.error("Error genereating audit configuration tables", ex);
        } catch (LiquibaseException ex) {
            logger.error("Error genereating audit configuration tables", ex);
        }
  
    }
    
    public void createAuditConfigTable2() {
        
        StringBuilder builder  = new StringBuilder();
        
        builder.append("create table ").append(this.unverifiedAuditConfigTable).append("(").append(System.lineSeparator());
        builder.append("...the rest of theh create script...this will generate an error");
        
        try (Connection conn = dataSource.getConnection()) {
            String schema = getSchema();
            if (null != schema) {
                conn.setSchema(schema);
            }

            Statement stmt = conn.createStatement();
            stmt.executeUpdate(builder.toString());
            
            stmt.close();
        } catch (SQLException ex) {
            logger.error("Error genereating audit configuration tables", ex);
        }
        
    }

    
    /**
     * Read the configuration attributes from the audit configuration
     * table in the target database/schema and return as a list
     *
     * @return A list of ConfigAttribute objects or an empty list if none are found.
     */
    @Override
    public List getConfigAttributes(){
        
        StringBuilder builder = new StringBuilder();
        builder.append("select attribute, table, column, value from ").append(verifiedAuditConfigTable);
                
        List<ConfigAttribute> attributes = new ArrayList();
        
        try {
            String schema = this.getSchema();    
            Connection conn = dataSource.getConnection();
            String defaultSchema = conn.getSchema();

            if ( schema != null){
                conn.setSchema(schema);
            }
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(builder.toString());
            
            while (rs.next()){
                //load attributes into configSource
                ConfigAttribute attrib = new ConfigAttribute();
                attrib.setAttribute(rs.getString("attribute"));
                attrib.setTableName(rs.getString("table"));
                attrib.setColumnName(rs.getString("column"));
                attrib.setValue(rs.getString("value"));
                
                attributes.add(attrib);
                
            }     
            
            conn.setSchema(defaultSchema);
            
            rs.close();
            stmt.close();
            conn.close();
            
        } catch (SQLException ex) {
            logger.error("Error retrieving audit configuration" + ex.getMessage());
        }
        
        return attributes;
             
    }
    
    /**
     * Get List of TableDef objects for all tables
     * in the targeted database/schema
     * 
     * @return ArrayList of TableDef objects or an empty list if none are found.
     */
    @Override
    public List getTables (){
     
        List<TableDef> tables = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection()){
            
            DatabaseMetaData dmd = conn.getMetaData();
            ResultSet rs = dmd.getTables(null, verifiedSchema, null, new String[]{"TABLE"});
            
            while (rs.next()){
                TableDef tableDef = new TableDef();
                tableDef.setName(rs.getString("TABLE_NAME").trim());
                tables.add(tableDef);
                
//                //ToDo: handle case where table full name matches the prefix or postfi
//                if ( table.toUpperCase().startsWith(configSource.getTablePrefix().toUpperCase())
//                     && table.toUpperCase().endsWith(configSource.getTablePostfix().toUpperCase())){
//                    configSource.addExistingAuditTable(table);
//                }
//                else {
//                    configSource.ensureTableConfig(table);
//                    
//                    //just in case audit config has set up the table with the
//                    //wrong case sensitivity, update the table name with the
//                    //value returned from the db
//                    TableConfig tc = configSource.getTableConfig(table);
//                    tc.setTableName(table);
//                }
            }
            
            rs.close();
            
        } catch (SQLException e){
            logger.error("SQL error retrieving table list: ", e);
            return null;
        }
        
        for ( TableDef tableDef : tables){
            tableDef.setColumns(getColumns(tableDef.getName()));
        }
        
        return tables;
 
    }
    
    /**
     * Get List of ColumnDef objects for all tables
     * in the targeted database/schema
     * 
     * @param tableName
     * @return ArrayList of ColumnDef objects or an empty list if none are found.
     */
    @Override
    public List getColumns (String tableName){
        
        List columns = new ArrayList<>();
        
        try {
            Connection conn = dataSource.getConnection();
            DatabaseMetaData dmd = conn.getMetaData();
            ResultSet rs = dmd.getColumns(null, verifiedSchema, tableName, null);
            
            //load all of the metadata in the result set into a map for each column
            
            ResultSetMetaData rsmd = rs.getMetaData();
            int metaDataColumnCount = rsmd.getColumnCount();
            if (! rs.isBeforeFirst()) {
                throw new RuntimeException("No results for DatabaseMetaData.getColumns(" + verifiedSchema + "." + tableName + ")");
            }
            while (rs.next()){
                ColumnDef columnDef = new ColumnDef();
                Map columnMetaData = new CaseInsensitiveMap();
                for (int i = 1; i <= metaDataColumnCount; i++){
                    columnMetaData.put(rsmd.getColumnName(i), rs.getString(i));
                }
                columnDef.setName(rs.getString("COLUMN_NAME"));
                columnDef.setType(rs.getString("TYPE_NAME"));
                columnDef.setSize(rs.getInt("COLUMN_SIZE"));
                columnDef.setDecimalSize(rs.getInt("DECIMAL_SIZE"));
                columnDef.setSourceMeta(columnMetaData);
                
                columns.add(columnDef);
            }
            
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
        
        return columns;
        
    }

   @Override
    public void setSchema(String unverifiedSchema) {

        this.unverifiedSchema = unverifiedSchema;
        this.verifiedSchema = null;
        
        if(unverifiedSchema == null){
            return;
        }
        
        try (Connection conn = dataSource.getConnection()){

            DatabaseMetaData dmd = conn.getMetaData();
            ResultSet rs = dmd.getSchemas();
            while (rs.next()) {
                if (rs.getString("TABLE_SCHEM").trim().equalsIgnoreCase(unverifiedSchema)) {
                    //store value with whatever case sensitivity it is returned as
                    verifiedSchema = rs.getString("TABLE_SCHEM").trim();
                }
            }
            rs.close();
        } catch (SQLException e) {
            logger.error("error verifying schema", e);
        }
    }
    
    @Override
    public String getSchema() {

        if (verifiedSchema == null
                && unverifiedSchema != null) {
            setSchema(unverifiedSchema);
        }

        return verifiedSchema;
    }
    
    @Override
    public void setAuditConfigTableName (String unverifiedTable){
        
        this.unverifiedAuditConfigTable = unverifiedTable;
        this.verifiedAuditConfigTable = null;
        String candidate = null;
        boolean multiMatch = false;
        
        if(unverifiedAuditConfigTable == null){
            return;
        }
        
        if (null == verifiedSchema){
            logger.error("attempting to verify auditConfigTable with unverified schema");
        }
        
        try (Connection conn = dataSource.getConnection()){

            DatabaseMetaData dmd = conn.getMetaData();
            ResultSet rs = dmd.getTables(null, null == verifiedSchema ? null : verifiedSchema, null, null);
            while (rs.next()) {
                if (rs.getString("TABLE_NAME").trim().equalsIgnoreCase(unverifiedTable)) {
                    //store value with whatever case sensitivity it is returned as
                    if (candidate == null){
                    candidate = rs.getString("TABLE_NAME").trim();
                    }
                    else{
                        multiMatch = true;
                    }
                }
            }
            rs.close();
        } catch (SQLException e) {
            logger.error("error verifying auditConfigTable", e);
        }
        
        /** Fails to set verified value if more than one match.
         * This can occur if schema is not set and there are multiple
         * tables in different schemas matching the table name.
         */
        if (!multiMatch){
            this.verifiedAuditConfigTable = candidate;
        }
        
    }
    
    @Override
    public String getAuditConfigTableName(){
         if (verifiedAuditConfigTable == null
                && unverifiedAuditConfigTable != null) {
            setAuditConfigTableName(unverifiedAuditConfigTable);
        }

        return verifiedAuditConfigTable;
    }

    @Override
    public void readDBChangeList(List<DBChangeUnit> units) {
        
        //The change list should be valid, and any code in this method
        //which is checking errors should not be required.  It is here
        //for early development sake, but the list really should be validated
        //before it is submitted here.
        
        List<DBChangeUnit> workList = null;
        Boolean beginTag = false;
        DBChangeType workListType = DBChangeType.notSet;
        
        if (!DBChangeUnit.validateUnitList(units)){
            logger.error("Invalid DBChangeUnitList submitted.  Not processing");
            return;
        }
        
        //pull apart each <begin-stuff-end> and submit into queue.
        for ( DBChangeUnit unit : units) {
            switch (unit.getChangeType()){
                case begin:
                    //start 'work unit list'
                    beginTag = true;
                    workList = new ArrayList<>();
                    workList.add(unit);
                    break;
                case end:
                    beginTag = false;
                    workList.add(unit);
                    //add to work queue
                    operations.add(workList);
                    break;
                case createTable:
                case alterTable:
                case createTriggers:
                case dropTriggers:
                    workListType = unit.getChangeType();
                    workList.add(unit);
                    break;
                case addColumn:                    
                case alterColumnName:
                case alterColumnSize:
                case alterColumnType:                    
                case addTriggerColumn:
                case fireOnInsert:
                case fireOnUpdate:
                case fireOnDelete:
                case addTriggerAction:
                case addTriggerTimeStamp:
                case addTriggerUser:
                    workList.add(unit);
                    break;
                case notSet:
                default:
                    //should not get here if the list is valid, unless a new changetype
                    //was added that this DMR does not know about.  If which case - fail.
                    logger.error ("unimplemented DBChangeUnit {%s}", unit.getChangeType().toString());
                    return;
                    
            }
            
        }

    }

    @Override
    public void executeChanges() {

        List<DBChangeUnit> op;


        while (!operations.isEmpty()) {
            op = operations.poll();

            //validate it one more time, totally not necessary :)
            if (!DBChangeUnit.validateUnitList(op)) {
                logger.error("Invalid DBChangeUnitList submitted.  Not processing");
            }
            
            switch (op.get(1).changeType) {
                case createTable:
                    executeCreateTable(op);
                    break;
                case alterTable:
                    executeAlterTable(op);
                    break;
                case createTriggers:
                    executeCreateTrigger(op);
                    break;
                case dropTriggers:
                    executeDropTrigger(op);
                default:
                    //should not get here if the list is valid, unless a new changetype
                    //was added that this DMR does not know about.  If which case - fail.
                    logger.error("unimplemented DBChangeUnit {%s}", op.get(1).getChangeType().toString());
                    return;
            }
        }
    }

    private void executeCreateTable(List<DBChangeUnit> op) {
        
        StringBuilder builder = new StringBuilder();
        StringBuilder constraints = new StringBuilder();
        boolean firstCol = true;

        for (DBChangeUnit unit : op) {
            switch (unit.changeType) {
                case begin:
                    //nothinig
                    break;
                case end:
                    builder.append(constraints);
                    builder.append(")").append(System.lineSeparator());
                    //execute SQL here...
                    break;
                case createTable:
                    builder.append("CREATE TABLE ").append(unit.tableName).append(" (").append(System.lineSeparator());
                    break;
                case addColumn:
                    if (!firstCol){
                        builder.append(", ");
                    }
                    if (unit.identity){
                        builder.append(unit.columnName).append(" ").append("serial PRIMARY KEY").append(System.lineSeparator());
                    }
                    else {
                        builder.append(unit.columnName).append(" ").append(unit.dataType);
                        if (unit.size > 0){
                            builder.append(" (").append(unit.size);
                        
                            if (unit.decimalSize > 0){
                                builder.append(",").append(unit.decimalSize);
                            }
                            builder.append(") ");
                        }
                        if (!unit.foreignTable.isEmpty()){
                            builder.append("REFERENCES ").append(unit.foreignTable).append(" (").append(unit.columnName).append(")");
                            //constraints.append("CONSTRAINT ").append(unit.columnName).append(" REFERENCES ").append(unit.foreignTable);
                        }
                        builder.append(System.lineSeparator());
                    }
                    break;
                default:
                    //should not get here if the list is valid, unless a new changetype
                    //was added that this DMR does not know about.  If which case - fail.
                    logger.error("unimplemented DBChangeUnit {%s} for alter table operation", unit.getChangeType().toString());
                    return;
            }
        }
        
        String schema = this.getSchema();
        
        try (Connection conn = dataSource.getConnection()) {
            String defaultSchema = conn.getSchema();
            if (null != schema) {
                conn.setSchema(schema);
            }
            Statement stmt = conn.createStatement();
            stmt.executeUpdate(builder.toString());
            stmt.close();

            //just in case this code is called with a pooled dataSource
            conn.setSchema(defaultSchema);
            
            
        } catch (SQLException ex) {
            logger.error("Create audit table failed...", ex);
        }
        
    }

    private void executeAlterTable(List<DBChangeUnit> op) {
        
        StringBuilder builder = new StringBuilder();
        StringBuilder constraints = new StringBuilder();
        boolean firstCol = true;

        for (DBChangeUnit unit : op) {
            switch (unit.changeType) {
                case begin:
                    //nothinig
                    break;
                case end:
                    builder.append(constraints);
                    //builder.append(")").append(System.lineSeparator());
                    //execute SQL here...
                    break;
                case alterTable:
                    builder.append("ALTER TABLE ").append(unit.tableName).append(System.lineSeparator());
                    break;
                case addColumn:
                    if (!firstCol){
                        builder.append(", ");
                    }
                    builder.append("ADD COLUMN ").append(unit.columnName).append(" ").append(unit.dataType);
                    if (unit.size > 0) {
                        builder.append(" (").append(unit.size);

                        if (unit.decimalSize > 0) {
                            builder.append(",").append(unit.decimalSize);
                        }
                        builder.append(") ");
                    }
                    builder.append(System.lineSeparator());
                case alterColumnSize:
                case alterColumnType:
                    if (!firstCol){
                        builder.append(", ");
                    }
                    builder.append("ALTER COLUMN ").append(unit.columnName).append(" ").append(unit.dataType);
                    if (unit.size > 0) {
                        builder.append(" (").append(unit.size);

                        if (unit.decimalSize > 0) {
                            builder.append(",").append(unit.decimalSize);
                        }
                        builder.append(") ");
                    }
                    builder.append(System.lineSeparator());
                    
                    break;
                default:
                    //should not get here if the list is valid, unless a new changetype
                    //was added that this DMR does not know about.  If which case - fail.
                    logger.error("unimplemented DBChangeUnit {%s} for drop trigger operation", unit.getChangeType().toString());
                    return;
            }
        }
        
        String schema = this.getSchema();
        
        try (Connection conn = dataSource.getConnection()) {
            String defaultSchema = conn.getSchema();
            if (null != schema) {
                conn.setSchema(schema);
            }
            Statement stmt = conn.createStatement();
            stmt.executeUpdate(builder.toString());
            stmt.close();

            //just in case this code is called with a pooled dataSource
            conn.setSchema(defaultSchema);
            
            
        } catch (SQLException ex) {
            logger.error("Alter audit table failed...", ex);
        }

    }

    private void executeCreateTrigger(List<DBChangeUnit> op) {
        
        StringBuilder builder = new StringBuilder();
        StringBuilder insertDetail = new StringBuilder();
        StringBuilder deleteDetail = new StringBuilder();
        StringBuilder updateDetail = new StringBuilder();
        StringBuilder updateConditional = new StringBuilder();
        String functionName = null;
        String triggerName = null;
        String triggerReference = null;
        String tableName = null;
        String auditTableName = null;
        String actionColumn = null;
        String userColumn = null;
        String timeStampColumn = null;
        boolean firstTrig = true;
        boolean onDelete = true;
        boolean onInsert = true;
        boolean onUpdate = true;
        List<String> columns = new ArrayList<>();
        List<String> whenColumns = new ArrayList<>();

        for (DBChangeUnit unit : op) {
            switch (unit.changeType) {
                case begin:
                    //nothinig
                    break;
                case end:
                    if (actionColumn == null || timeStampColumn == null || userColumn == null){
                        logger.error("Trigger info for table %s missing audit columns for: %s %s %s",
                                tableName, actionColumn == null ? "action" : "",
                                timeStampColumn == null ? "timeStamp" : "",
                                userColumn == null ? "user" : "");
                        return;
                    }
                    
                    //////////////////////
                    //generate the when clause for the update trigger
                    if (columns.size() > whenColumns.size() ){
                        //some columns excluded from update
                        updateConditional.append("AND (");
                        boolean firstCol = true;
                        for (String col : whenColumns){
                            if (!firstCol){
                                updateConditional.append("            OR ");
                            }
                            updateConditional.append("OLD.").append(unit.getColumnName()).append(" IS DISTINCT FROM NEW.").append(unit.getColumnName()).append(System.lineSeparator());
                        }
                        updateConditional.append(")) THEN").append(System.lineSeparator());                       
                    }
                    else {
                        //no column conditions.  Alwasy insert audit row on update
                        updateConditional.append(") THEN").append(System.lineSeparator());
                    }
                    
                    //////////////////////                     
                    //generate the detail insert column list for the trigger(s)
                    insertDetail.append(String.format("        INSERT INTO %s (%s, %s, %s", auditTableName, actionColumn, userColumn, timeStampColumn));
                    updateDetail.append(String.format("        INSERT INTO %s (%s, %s, %s", auditTableName, actionColumn, userColumn, timeStampColumn));
                    deleteDetail.append(String.format("        INSERT INTO %s (%s, %s, %s", auditTableName, actionColumn, userColumn, timeStampColumn));
                    for (String col : columns){
                        insertDetail.append(", ").append(col);
                        updateDetail.append(", ").append(col);
                        deleteDetail.append(", ").append(col);
                    }
                    insertDetail.append(")").append(System.lineSeparator());
                    updateDetail.append(")").append(System.lineSeparator());
                    deleteDetail.append(")").append(System.lineSeparator());
                    
                    //////////////////////
                    //generate the insert column valuues for the trigger(s)
                    insertDetail.append("        VALUES SELECT 'insert', user, now()");
                    updateDetail.append("        VALUES SELECT 'update', user, now()");
                    deleteDetail.append("        VALUES SELECT 'delete', user, now()");
                    for (String col : columns){
                        insertDetail.append(", NEW.").append(col);
                        updateDetail.append(", NEW.").append(col);
                        deleteDetail.append(", OLD.").append(col);
                    }
                    insertDetail.append(")").append(System.lineSeparator());
                    updateDetail.append(")").append(System.lineSeparator());
                    deleteDetail.append(")").append(System.lineSeparator());
                    insertDetail.append("        RETURN NEW;").append(System.lineSeparator());
                    updateDetail.append("        RETURN NEW;").append(System.lineSeparator());
                    deleteDetail.append("        RETURN OLD;").append(System.lineSeparator());
                    
                    //////////////////////
                    //creat the function that the trigger calls
                    builder.append("CREATE OR REPLACE FUNCTION ").append(functionName).append(" RETURNS TRIGGER AS ")
                            .append(triggerReference).append(System.lineSeparator());
                    builder.append("BEGIN").append(System.lineSeparator());
                    builder.append("    IF (TG_OP = 'DELETE') THEN").append(System.lineSeparator());
                    builder.append(deleteDetail);                    
                    builder.append("    ELSEIF (TG_OP = 'INSERT') THEN").append(System.lineSeparator());
                    builder.append(insertDetail);
                    builder.append("    ELSEIF (TG_OP = 'UPDATE' ").append(updateConditional).append(System.lineSeparator());
                    builder.append(updateDetail);
                    builder.append("    ENDIF;").append(System.lineSeparator());
                    builder.append("END").append(System.lineSeparator());
                    builder.append(triggerReference).append(" LANGUAGE plpgsql;").append(System.lineSeparator());
                    
                    ///////////////////////                    
                    //create the trigger
                    builder.append("CREATE TRIGGER ").append(triggerName).append(System.lineSeparator());
                    builder.append("AFTER ");
                    if (onInsert){
                        builder.append("INSERT ");
                        firstTrig = false;
                    }
                    if (onUpdate){
                        if (!firstTrig){
                            builder.append("OR UPDATE ");
                        }
                        else {
                            builder.append("UPDATE ");
                            firstTrig = false;
                        }
                    }
                    if (onDelete){
                        if (!firstTrig){
                            builder.append("OR DELETE ");
                        }
                        else {
                            builder.append("DELETE ");
                        }
                    }
                    builder.append("ON ").append(tableName).append(System.lineSeparator());
                    builder.append("FOR EACH ROW EXECUTE PROCEDURE ").append(functionName).append("();").append(System.lineSeparator());
                    //run the sql...
                    break;
                case createTriggers:
                    tableName = unit.getTableName();
                    triggerName = unit.getTableName() + "_audit";
                    functionName = "process_" + triggerName;
                    triggerReference = "$" + triggerName + "$";
                    auditTableName = unit.getAuditTableName();
                    break;
                case fireOnDelete:
                    onDelete = unit.getFiresTrigger();
                    break;
                case fireOnInsert:
                    onInsert = unit.getFiresTrigger();
                    break;
                case fireOnUpdate:
                    onUpdate = unit.getFiresTrigger();
                    break;
                case addTriggerColumn:
                    if (unit.firesTrigger){
                        whenColumns.add(unit.getColumnName());
                    }
                    columns.add(unit.columnName);
                    break;
                case addTriggerAction:
                    actionColumn = unit.getColumnName();
                    break;
                case addTriggerUser:
                    userColumn = unit.getColumnName();
                    break;
                case addTriggerTimeStamp:
                    timeStampColumn = unit.getColumnName();
                    break;
                default:
                    //should not get here if the list is valid, unless a new changetype
                    //was added that this DMR does not know about.  If which case - fail.
                    logger.error("unimplemented DBChangeUnit {%s} for create table operation", unit.getChangeType().toString());
                    return;
            }
        }
        
        String schema = this.getSchema();
        
        try (Connection conn = dataSource.getConnection()) {
            String defaultSchema = conn.getSchema();
            if (null != schema) {
                conn.setSchema(schema);
            }
            Statement stmt = conn.createStatement();
            stmt.executeUpdate(builder.toString());
            stmt.close();

            //just in case this code is called with a pooled dataSource
            conn.setSchema(defaultSchema);
            
            
        } catch (SQLException ex) {
            logger.error("Create triggers failed...", ex);
        }
    }

    private void executeDropTrigger(List<DBChangeUnit> op) {
        
        StringBuilder builder = new StringBuilder();
        String triggerName;

        for (DBChangeUnit unit : op) {
            switch (unit.changeType) {
                case begin:
                    //nothinig
                    break;
                case end:
                    //run the sql...
                    break;
                case dropTriggers:
                    triggerName = unit.tableName + "audit";
                    builder.append("DROP TRIGGER IF EXISTS").append(triggerName).append("ON ").append(unit.tableName).append(";").append(System.lineSeparator());
                    break;
                default:
                    //should not get here if the list is valid, unless a new changetype
                    //was added that this DMR does not know about.  If which case - fail.
                    logger.error("unimplemented DBChangeUnit {%s} for create table operation", unit.getChangeType().toString());
                    return;
            }
        }
        
        String schema = this.getSchema();
        
        try (Connection conn = dataSource.getConnection()) {
            String defaultSchema = conn.getSchema();
            if (null != schema) {
                conn.setSchema(schema);
            }
            Statement stmt = conn.createStatement();
            stmt.executeUpdate(builder.toString());
            stmt.close();

            //just in case this code is called with a pooled dataSource
            conn.setSchema(defaultSchema);
            
            
        } catch (SQLException ex) {
            logger.error("Drop trigger failed...", ex);
        }
    }
        

    @Override
    public void executeDBChangeList(List<DBChangeUnit> units) {
        readDBChangeList(units);
        executeChanges();
    }

    @Override
    public void purgeDBChanges() {
        operations.clear();
    }

    @Override
    public int getMaxUserNameLength() {
        
        Integer length = -1;
         try (Connection conn = dataSource.getConnection()){

            DatabaseMetaData dmd = conn.getMetaData();
            length = dmd.getMaxUserNameLength();
            
        } catch (SQLException e) {
            logger.error("error getting maxUserNameLength", e);
        }

         return length;
    }

}
