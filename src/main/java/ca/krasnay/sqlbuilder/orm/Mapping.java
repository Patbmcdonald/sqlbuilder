package ca.krasnay.sqlbuilder.orm;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import ca.krasnay.sqlbuilder.DeleteCreator;
import ca.krasnay.sqlbuilder.InsertCreator;
import ca.krasnay.sqlbuilder.Predicate;
import ca.krasnay.sqlbuilder.SelectCreator;
import ca.krasnay.sqlbuilder.UpdateCreator;

/**
 * A mapping maps a Java class to a database table and the fields in the class
 * to columns in the table. Typically, you would create a DAO that creates and
 * holds the mapping for a particular entity class. DAO methods would delegate
 * to the equivalent mapping methods.
 *
 * Each mapping must be provided with an {@link OrmConfig} object representing
 * the ORM environment, including which data source and {@link IdSource} to use.
 *
 * There are a number of features of a full-fledged ORM tool such Hibernate that
 * this class <i>doesn't</i> try to do:
 *
 * <ul>
 * <li>Manage associations.
 * <li>Map a class hierarchy between one or more tables.
 * <li>Map a field across multiple columns, or vice versa.
 * <li>Implement object caching.
 * <li>Automated DDL (creating tables and columns where necessary).
 * </ul>
 *
 * We believe that these features are the cause of most of the complexity and
 * therefore confusion that arises from tools such as Hibernate.
 *
 */
public class Mapping<T> {

    /**
     * Delete object returned by {@link Mapping#beginDelete()}.
     *
     * @author <a href="mailto:john@krasnay.ca">John Krasnay</a>
     */
    public class Delete {

        private DeleteCreator delete;

        private Delete() {
            this.delete = new DeleteCreator(table);
        }

//        public Delete setParameter(String name, Object value) {
//            delete.setParameter(name, value);
//            return this;
//        }

        public Delete and(String expr) {
            return where(expr);
        }

        public Delete and(Predicate predicate) {
            return where(predicate);
        }

        public int delete() {
            return new JdbcTemplate(ormConfig.getDataSource()).update(delete);
        }

        public Delete where(String expr) {
            delete.where(expr);
            return this;
        }

        public Delete where(Predicate predicate) {
            delete.where(predicate);
            return this;
        }

        public Delete whereEquals(String expr, Object value) {
            delete.whereEquals(expr, value);
            return this;
        }


    }

    /**
     * Query object returned by Mapping#createQuery.
     */
    public class Query {

        private SelectCreator select;

        private Query() {
            this.select = new SelectCreator().from(table);
        }

        public Query and(Predicate predicate) {
            return where(predicate);
        }

        public Query and(String expr) {
            return where(expr);
        }

        public Query forUpdate() {
            select.forUpdate();
            return this;
        }

        public List<T> getResultList() {

            select.column(idColumn.getColumnName());

            if (versionColumn != null) {
                select.column(versionColumn.getColumnName());
            }

            for (Column column : columns) {
                select.column(column.getColumnName());
            }

            return new JdbcTemplate(ormConfig.getDataSource()).query(select, new RowMapper<T>() {
                @Override
                public T mapRow(ResultSet rs, int row) throws SQLException {

                    T result = createInstance();

                    setFieldValueFromResultSet(result, rs, idColumn);

                    if (versionColumn != null) {
                        setFieldValueFromResultSet(result, rs, versionColumn);
                    }

                    for (Column column : columns) {
                        setFieldValueFromResultSet(result, rs, column);
                    }

                    return result;
                }

            });

        }

        /**
         * Returns a single result from the query.
         *
         * @throws SingleResultException
         *             if the query returned no rows or more than one row
         */
        public T getSingleResult() throws SingleResultException {
            List<T> results = getResultList();
            if (results.size() == 1) {
                return results.get(0);
            } else {
                throw new SingleResultException(results.size(), select);
            }
        }

        /**
         * Returns a single result from the query. If now matching records were
         * found, returns null.
         *
         * @throws SingleResultException
         *             if the query returned more than one row
         */
        public T getSingleResultOrNull() throws SingleResultException {
            List<T> results = getResultList();
            if (results.size() == 1) {
                return results.get(0);
            } else if (results.size() == 0) {
                return null;
            } else {
                throw new SingleResultException(results.size(), select);
            }
        }

        public Query orderBy(String expr) {
            select.orderBy(expr);
            return this;
        }

        public Query orderBy(String expr, boolean ascending) {
            select.orderBy(expr, ascending);
            return this;
        }

        public Query noWait() {
            select.noWait();
            return this;
        }

        public Query setParameter(String name, Object value) {
            select.setParameter(name, value);
            return this;
        }

        public Query where(Predicate predicate) {
            select.where(predicate);
            return this;
        }

        public Query where(String expr) {
            select.where(expr);
            return this;
        }

        public Query whereEquals(String expr, Object value) {
            select.whereEquals(expr, value);
            return this;
        }

    }

    public static final long NULL_ID = 0;

    private OrmConfig ormConfig;

    private Class<T> clazz;

    private String table;

    private Column idColumn;

    private Column versionColumn;

    private List<Column> columns = new ArrayList<Column>();

    private List<String> ignoredFields = new ArrayList<String>();

    public Mapping(OrmConfig ormConfig, Class<T> clazz, String table) {
        this.ormConfig = ormConfig;
        this.clazz = clazz;
        this.table = table;
    }

    public Mapping<T> addColumn(Column column) {
        columns.add(column);
        return this;
    }

    public Mapping<T> addColumn(String fieldName) {
        addColumn(new Column(fieldName));
        return this;
    }

    public Mapping<T> addColumn(String fieldName, String columnName) {
        addColumn(new Column(fieldName, columnName));
        return this;
    }

    public Mapping<T> addColumn(String fieldName, Converter<?> converter) {
        addColumn(new Column(fieldName, converter));
        return this;
    }

    public Mapping<T> addColumn(String fieldName, String columnName, Converter<?> converter) {
        addColumn(new Column(fieldName, columnName, converter));
        return this;
    }

    /**
     * Adds mappings for each declared field in the mapped class. Any fields
     * already mapped by addColumn are skipped.
     */
    public Mapping<T> addFields() {

        if (idColumn == null) {
            throw new RuntimeException("Map ID column before adding class fields");
        }

        for (Field f : ReflectionUtils.getDeclaredFieldsInHierarchy(clazz)) {
            if (!isFieldMapped(f.getName())
                    && !ignoredFields.contains(f.getName())) {
                addColumn(f.getName());
            }
        }

        return this;
    }

    /**
     * Creates a Delete object. You can add criteria to the Delete object,
     * finally calling the Delete
     * @return
     */
    public Delete beginDelete() {
        return new Delete();
    }

    /**
     * Creates instance of the entity class. This method is called to create the object
     * instances when returning query results.
     */
    protected T createInstance() {
        try {
            Constructor<T> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a Query object.
     */
    public Query createQuery() {
        return new Query();
    }

    /**
     * Deletes an entity by its primary key.
     *
     * @param id
     *            Primary key of the entity.
     */
    public void deleteById(Object id) {

        int count = beginDelete().whereEquals(idColumn.getColumnName(), id).delete();

        if (count == 0) {
            throw new RowNotFoundException(table, id);
        }
    }

    /**
     * Finds an entity given its primary key.
     *
     * @throws RowNotFoundException
     *             If no such object was found.
     */
    public T findById(Object id) throws RowNotFoundException {

        List<T> result = createQuery().whereEquals(idColumn.getColumnName(), id).getResultList();

        if (result.size() == 0) {
            throw new RowNotFoundException(table, id);
        } else {
            return result.get(0);
        }
    }

    private Converter<?> getConverter(Column column) {
        if (column.getConverter() != null) {
            return column.getConverter();
        } else {
            Field field = ReflectionUtils.getDeclaredFieldWithPath(clazz, column.getFieldName());
            return ormConfig.getConverterFactory().getConverter(field.getType());
        }
    }

    public Column getIdColumn() {
        return idColumn;
    }

    @SuppressWarnings("unchecked")
    private Object getFieldValueAsColumn(T entity, Column column) {
        Object fieldValue = ReflectionUtils.getFieldValueWithPath(entity, column.getFieldName());
        @SuppressWarnings("rawtypes")
        Converter converter = getConverter(column);
        return converter.convertFieldValueToColumn(fieldValue);
    }

    /**
     * Returns the primary key value of the entity.
     */
    public Object getPrimaryKey(T entity) {
        return ReflectionUtils.getFieldValue(entity, idColumn.getFieldName());
    }

    public String getTable() {
        return table;
    }

    private int getVersion(T entity) {
        Object value = ReflectionUtils.getFieldValue(entity, versionColumn.getFieldName());
        return ((Number) value).intValue();
    }

    /**
     * Indicates that the given field is to be ignored by a subsequent
     * invocation of {@link #addFields()}.
     *
     * @param fieldName
     *            Name of the field to ignore.
     */
    public Mapping<T> ignoreField(String fieldName) {
        ignoredFields.add(fieldName);
        return this;
    }

    /**
     * Insert entity object. Its "id" field gets populated in the process.
     */
    public T insert(T entity) {

        if (isPersistent(entity)) {
            throw new RuntimeException(String.format("Tried to insert object of type %s with existing id %d",
                    entity.getClass().getSimpleName(), getPrimaryKey(entity)));
        }

        Object id = ormConfig.getIdSourceFactory().getIdSource(ormConfig.getDataSource(), this).nextId();

        InsertCreator insert = new InsertCreator(table);

        insert.setValue(idColumn.getColumnName(), id);

        if (versionColumn != null) {
            insert.setValue(versionColumn.getColumnName(), 0);
        }

        for (Column column : columns) {
            insert.setValue(column.getColumnName(), getFieldValueAsColumn(entity, column));
        }

        new JdbcTemplate(ormConfig.getDataSource()).update(insert);

        ReflectionUtils.setFieldValue(entity, idColumn.getFieldName(), id);

        if (versionColumn != null) {
            ReflectionUtils.setFieldValue(entity, versionColumn.getFieldName(), 0);
        }

        return entity;
    }

    private boolean isFieldMapped(String fieldName) {

        if (fieldName.equals(idColumn.getFieldName())) {
            return true;
        }

        if (versionColumn != null &&  fieldName.equals(versionColumn.getFieldName())) {
            return true;
        }

        for (Column column : columns) {
            if (column.getFieldName().equals(fieldName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true if this entity has already been inserted into the database.
     * By default, returns true if the primary key is not null, and for numeric
     * fields, is non-zero.
     */
    public boolean isPersistent(T entity) {
        Object pk = getPrimaryKey(entity);
        if (pk == null) {
            return false;
        } else {
            if (pk instanceof Number && ((Number) pk).longValue() == 0) {
                return false;
            }
        }
        return true;
    }

    public void setFieldValueFromResultSet(T entity, ResultSet rs, Column column) {
        try {
            @SuppressWarnings("rawtypes")
            Converter converter = getConverter(column);
            Object fieldValue = converter.getFieldValueFromResultSet(rs, column.getColumnName());
            ReflectionUtils.setFieldValueWithPath(entity, column.getFieldName(), fieldValue);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Mapping<T> setIdColumn(Column idColumn) {
        this.idColumn = idColumn;
        return this;
    }

    public Mapping<T> setIdColumn(String idColumnName) {
        this.idColumn = new Column(idColumnName);
        return this;
    }

    public Mapping<T> setVersionColumn(Column versionColumn) {
        this.versionColumn = versionColumn;
        return this;
    }

    public Mapping<T> setVersionColumn(String versionColumnName) {
        this.versionColumn = new Column(versionColumnName);
        return this;
    }

    /**
     * Updates value of entity in the table.
     */
    public T update(T entity) throws RowNotFoundException, OptimisticLockException {

        if (!isPersistent(entity)) {
            throw new RuntimeException(String.format("Tried to update non-persistent object of type %s", entity
                    .getClass().getSimpleName()));
        }

        UpdateCreator update = new UpdateCreator(table);

        update.whereEquals(idColumn.getColumnName(), getPrimaryKey(entity));

        if (versionColumn != null) {
            update.set(versionColumn.getColumnName() + " = " + versionColumn.getColumnName() + " + 1");
            update.whereEquals(versionColumn.getColumnName(), getVersion(entity));
        }

        for (Column column : columns) {
            update.setValue(column.getColumnName(), getFieldValueAsColumn(entity, column));
        }

        int rows = new JdbcTemplate(ormConfig.getDataSource()).update(update);

        if (rows == 1) {

            if (versionColumn != null) {
                ReflectionUtils.setFieldValue(entity, versionColumn.getFieldName(), getVersion(entity) + 1);
            }

            return entity;

        } else if (rows > 1) {

            throw new RuntimeException(
                    String.format("Updating table %s with id %s updated %d rows. There must be a mapping problem. Is column %s really the primary key?",
                            table, getPrimaryKey(entity), rows, idColumn));

        } else {

            //
            // Updated zero rows. This could be because our ID is wrong, or
            // because our object is out-of date. Let's try querying just by ID.
            //

            SelectCreator selectById = new SelectCreator()
            .column("count(*)")
            .from(table)
            .whereEquals(idColumn.getColumnName(), getPrimaryKey(entity));

            rows = new JdbcTemplate(ormConfig.getDataSource()).query(selectById, new ResultSetExtractor<Integer>() {
                @Override
                public Integer extractData(ResultSet rs) throws SQLException, DataAccessException {
                    rs.next();
                    return rs.getInt(1);
                }
            });

            if (rows == 0) {
                throw new RowNotFoundException(table, getPrimaryKey(entity));
            } else {
                throw new OptimisticLockException(table, getPrimaryKey(entity));
            }
        }
    }
}
