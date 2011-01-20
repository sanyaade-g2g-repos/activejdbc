/*
Copyright 2009-2010 Igor Polevoy 

Licensed under the Apache License, Version 2.0 (the "License"); 
you may not use this file except in compliance with the License. 
You may obtain a copy of the License at 

http://www.apache.org/licenses/LICENSE-2.0 

Unless required by applicable law or agreed to in writing, software 
distributed under the License is distributed on an "AS IS" BASIS, 
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
See the License for the specific language governing permissions and 
limitations under the License. 
*/


package activejdbc;

import activejdbc.annotations.*;
import activejdbc.associations.*;
import activejdbc.cache.CacheManager;
import activejdbc.cache.QueryCache;
import activejdbc.statistics.StatisticsQueue;
import activejdbc.validation.Validator;

import java.util.*;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.ResultSet;

import javalite.common.Inflector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Igor Polevoy
 */
public class Registry {

    private final static Logger logger = LoggerFactory.getLogger(Registry.class);
    private static Registry instance;
    private final static HashMap<Class, List<Validator>> validators = new HashMap<Class, List<Validator>>();
    private final static HashMap<Class, List<CallbackListener>> listeners = new HashMap<Class, List<CallbackListener>>();
    private MetaModels metaModels = new MetaModels();
    private Configuration configuration = new Configuration();
    private StatisticsQueue statisticsQueue;
    private static ModelFinder mf = new ModelFinder();
    private List<String> initedDbs = new ArrayList<String>();

    private Registry() {
        if(configuration.collectStatistics()){
            statisticsQueue = new StatisticsQueue();
            statisticsQueue.start();
        }

    }

    public static Registry instance() {
        if(instance == null){
            instance = new Registry();
        }
        return instance;
    }

    public StatisticsQueue getStatisticsQueue(){
        if(statisticsQueue == null){
            throw new InitException("cannot collect statistics if this was not configured in activejdbc.properties file. Add 'collectStatistics = true' to it.");
        }
        return statisticsQueue;
    }

    public Configuration getConfiguration(){
        return configuration;
    }

    public static CacheManager cacheManager(){
        return QueryCache.instance().getCacheManager();
    }

    /**
     * Provides a MetaModel of a model representing a table. 
     *
     * @param table name of table represented by this MetaModel.
     * @return MetaModel of a model representing a table.
     */
    public MetaModel getMetaModel(String table) {
        return metaModels.getMetaModel(table);
    }

    /**
     * Returns MetaModel associated with a model class.
     *
     * @param className class name of a model.
     * @return MetaModel associated with a model class, null if not found.
     */
    public MetaModel getMetaModelByClassName(String className) {

        String dbName;
        try {
            dbName = MetaModel.getDbName((Class<? extends Model>) Class.forName(className));
        } catch (Exception e) {
            throw new InitException(e);
        }
        init(dbName);

        return metaModels.getMetaModelByClassName(className);
    }


    public MetaModel getMetaModel(Class<? extends Model> modelClass) {

        String dbName = MetaModel.getDbName(modelClass);
        init(dbName);

        return metaModels.getMetaModel(modelClass);
    }


     void init(String dbName) {
        if (initedDbs.contains(dbName)) return;

         initedDbs.add(dbName);

        try {
            mf.findModels();
            String dbType = ConnectionsAccess.getConnection(dbName).getMetaData().getDatabaseProductName();
            registerModels(dbName, mf.getModelsForDb(dbName), dbType);
            String[] tables = metaModels.getTableNames(dbName);

            for (String table : tables) {
                Map<String, ColumnMetadata> metaParams = fetchMetaParams(table, dbName);
                registerColumnMetadata(table, metaParams);
            }

            processOverrides(mf.getModelsForDb(dbName));

            for (String table : tables) {
                discoverAssociationsFor(table, dbName);
            }
        } catch (Exception e) {
            if (e instanceof InitException) throw (InitException) e;
            if (e instanceof DBException) throw (DBException) e;
            else throw new InitException(e);
        }
    }

    /**
     *
     * @param modelClasses
     * @param dbType this is a name of a DBMS as returned by JDBC driver, such as Oracle, MySQL, etc.
     */
    private void registerModels(String dbName, List<Class<? extends Model>> modelClasses, String dbType) {
        
        for (Class<? extends Model> modelClass : modelClasses) {
            String idName = findIdName(modelClass);
            String tableName = findTableName(modelClass);
            String idGeneratorCode= findIdGeneratorCode(modelClass);
            MetaModel mm = new MetaModel(dbName, tableName, idName, modelClass, dbType, isCached(modelClass), idGeneratorCode);
            metaModels.addMetaModel(mm, tableName, modelClass);
            LogFilter.log(logger, "Registered model: " + modelClass);
        }
    }

    private boolean isCached(Class<? extends Model> modelClass) {
        return null != modelClass.getAnnotation(Cached.class);  
    }

    private void processOverrides(List<Class<? extends Model>> models) {

        for(Class<? extends Model> modelClass : models){

            BelongsTo belongsToAnnotation = modelClass.getAnnotation(BelongsTo.class);
            processOverridesBelongsTo(modelClass, belongsToAnnotation);
            
            BelongsToParents belongsToCollectionAnotation = modelClass.getAnnotation(BelongsToParents.class);
            if (belongsToCollectionAnotation != null) 
            	for (BelongsTo belongsTo : belongsToCollectionAnotation.value())
            		processOverridesBelongsTo(modelClass, belongsTo);
            
            Many2Many many2manyAnnotation = modelClass.getAnnotation(Many2Many.class);

            if(many2manyAnnotation != null){

                Class<? extends Model> otherClass = many2manyAnnotation.other();

                String source = getTableName(modelClass);
                String target = getTableName(otherClass);
                String join = many2manyAnnotation.join();
                String sourceFKName = many2manyAnnotation.sourceFKName();
                String targetFKName = many2manyAnnotation.targetFKName();

                Association many2many1 = new Many2ManyAssociation(source, target, join, sourceFKName, targetFKName);
                metaModels.getMetaModel(source).addAssociation(many2many1);

                Association many2many2 = new Many2ManyAssociation(target, source, join, targetFKName, sourceFKName);
                metaModels.getMetaModel(target).addAssociation(many2many2);
            }

            BelongsToPolymorphic belongsToPolymorphic = modelClass.getAnnotation(BelongsToPolymorphic.class);
            if(belongsToPolymorphic != null){

                Class<? extends Model>[] parentClasses = belongsToPolymorphic.parents();
                for (Class<? extends Model> parentClass: parentClasses) {
                    BelongsToPolymorphicAssociation belongsToPolymorphicAssociation =
                            new BelongsToPolymorphicAssociation(getTableName(modelClass),
                                    getTableName(parentClass), parentClass.getName());
                    metaModels.getMetaModel(modelClass).addAssociation(belongsToPolymorphicAssociation);


                    OneToManyPolymorphicAssociation oneToManyPolymorphicAssociation =
                            new OneToManyPolymorphicAssociation(getTableName(parentClass),
                                    getTableName(modelClass), parentClass.getName());
                    metaModels.getMetaModel(parentClass).addAssociation(oneToManyPolymorphicAssociation);
                }
            }
        }
    }
    
    private void processOverridesBelongsTo(Class<? extends Model> modelClass, BelongsTo belongsToAnnotation) {
        if(belongsToAnnotation != null){
            Class<? extends Model> parentClass = belongsToAnnotation.parent();
            String foreignKeyName = belongsToAnnotation.foreignKeyName();
            Association hasMany = new OneToManyAssociation(getTableName(parentClass), getTableName(modelClass), foreignKeyName);
            Association belongsTo = new BelongsToAssociation(getTableName(modelClass), getTableName(parentClass), foreignKeyName);

            metaModels.getMetaModel(parentClass).addAssociation(hasMany);
            metaModels.getMetaModel(modelClass).addAssociation(belongsTo);
        }
	}

    /**
     * Returns a hash keyed off a column name.
     *
     * @return
     * @throws java.sql.SQLException
     */
    private Map<String, ColumnMetadata> fetchMetaParams(String table, String dbName) throws SQLException {
        Connection con = ConnectionsAccess.getConnection(dbName);

        String schemaName = System.getProperty("activejdbc.schema.name");

        //try upper case table name first - Oracle uses upper case
        ResultSet rs = con.getMetaData().getColumns(null, schemaName != null?schemaName:null, table.toUpperCase(), null);
        Map<String, ColumnMetadata> columns = getColumns(rs);
        rs.close();
        //if upper case not found, try lower case.
        if(columns.size() == 0){
            rs = con.getMetaData().getColumns(null, schemaName != null?schemaName:null, table.toLowerCase(), null);
            columns = getColumns(rs);
            rs.close();
        }

        if(columns.size() > 0){
            LogFilter.log(logger, "Fetched metadata for table: " + table);
        }
        else{
            logger.warn("Failed to retrieve metadata for table: '" + table
                + "'. Are you sure this table exists? For some databases table names are case sensitive.");
        }

        return columns;
    }

    private Map<String, ColumnMetadata> getColumns(ResultSet rs) throws SQLException {
         Map<String, ColumnMetadata> columns = new HashMap<String, ColumnMetadata>();
        while (rs.next()) {
            ColumnMetadata cm = new ColumnMetadata(rs.getString("COLUMN_NAME").toLowerCase(), rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"));
            columns.put(cm.getColumnName(), cm);
        }
        return columns;
    }

    private void discoverAssociationsFor(String source, String dbName) {
        discoverOne2ManyAssociationsFor(source, dbName);
        discoverMany2ManyAssociationsFor(source, dbName);
    }

    private void discoverMany2ManyAssociationsFor(String source, String dbName) {
        for (String join : metaModels.getTableNames(dbName)) {
            String other = Inflector.getOtherName(source, join);
            if (other == null || getMetaModel(other) == null || !hasForeignKeys(join, source, other))
                continue;

            Association associationSource = new Many2ManyAssociation(source, other, join, 
                    getMetaModel(source).getFKName(), getMetaModel(other).getFKName());
            getMetaModel(source).addAssociation(associationSource);
        }
    }

    /**
     * Checks that the "join" table has foreign keys from "source" and "other" tables. Returns true
     * if "join" table exists and contains foreign keys of "source" and "other" tables, false otherwise.
     *
     * @param join   - potential name of a join table.
     * @param source name of a "source" table
     * @param other  name of "other" table.
     * @return true if "join" table exists and contains foreign keys of "source" and "other" tables, false otherwise.
     */
    private boolean hasForeignKeys(String join, String source, String other) {
        String sourceFKName = getMetaModel(source).getFKName();
        String otherFKName = getMetaModel(other).getFKName();
        MetaModel joinMM = getMetaModel(join);
        return joinMM.hasAttribute(sourceFKName) && joinMM.hasAttribute(otherFKName);
    }


    /**
     * Discover many to many associations.
     *
     * @param source name of table for which associations are searched.
     */
    private void discoverOne2ManyAssociationsFor(String source, String dbName) {

        MetaModel sourceMM = getMetaModel(source);

        for (String target : metaModels.getTableNames(dbName)) {
            MetaModel targetMM = getMetaModel(target);

            String sourceFKName = getMetaModel(source).getFKName();
            if (targetMM != sourceMM && targetMM.hasAttribute(sourceFKName)) {
                targetMM.addAssociation(new BelongsToAssociation(target, source, sourceFKName));
                sourceMM.addAssociation(new OneToManyAssociation(source, target, sourceFKName));
            }
        }
    }


    private String findIdGeneratorCode(Class<? extends Model> modelClass) {
        IdGenerator idGenerator = modelClass.getAnnotation(IdGenerator.class);
        return idGenerator == null ? null : idGenerator.value();
    }

    private String findIdName(Class<? extends Model> modelClass) {
        IdName idNameAnnotation = modelClass.getAnnotation(IdName.class);
        return idNameAnnotation == null ? "id" : idNameAnnotation.value();
    }

    private String findTableName(Class<? extends Model> modelClass) {
        Table tableAnnotation = modelClass.getAnnotation(Table.class);
        return tableAnnotation == null ? Inflector.tableize(Inflector.shortName(modelClass.getName())) : tableAnnotation.value();
    }


    public Class<? extends Model> getModelClass(String table) {
        Class modelClass = metaModels.getModelClass(table);

        if(modelClass == null)        
            throw new InitException("failed to locate meta model for: " + table + ", are you sure this is correct table name?");

        return modelClass;
    }

    protected String getTableName(Class<? extends Model> modelClass) {

        String tableName = metaModels.getTableName(modelClass);
        if (tableName == null) {
            throw new DBException("failed to find metamodel for " + modelClass + ". Are you sure that a corresponding table  exists in DB?");
        }
        return tableName;
    }

    protected List<Validator> getValidators(Class<Model> daClass) {

        //TODO: this can be optimized - cached
        List<Validator> validatorList = validators.get(daClass);
        if (validatorList == null) {
            validatorList = new ArrayList<Validator>();
            validators.put(daClass, validatorList);
        }
        return validatorList;
    }

    public void addValidators(Class<Model> daClass, List<? extends Validator> modelValidators) {
        getValidators(daClass).addAll(modelValidators);
    }

    public void removeValidator(Class<Model> daClass, Validator validator) {
        getValidators(daClass).remove(validator);
    }

    /**
     * Returns edges for a join. An edge is a table in a many to many relationship that is not a join.
     *
     * We have to go through all the associations here because join tables, (even if the model exists) will not
     * have associations to the edges.
     *
     * @param join name of join table;
     * @return edges for a join
     */
    protected List<String> getEdges(String join){

        return metaModels.getEdges(join);

    }

    private void registerColumnMetadata(String table, Map<String, ColumnMetadata> metaParams) {
        metaModels.setColumnMetadata(table, metaParams);
    }

    protected List<CallbackListener> getListeners(Class modelClass){
        if(listeners.get(modelClass) == null){
            listeners.put(modelClass, new ArrayList<CallbackListener>());
        }
        return listeners.get(modelClass); 
    }
    public void addListener(Class modelClass, CallbackListener listener) {
        getListeners(modelClass).add(listener);
    }
}
