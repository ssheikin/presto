/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.mongodb;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.trino.plugin.mongodb.MongoIndex.MongodbIndexKey;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorNewTableLayout;
import io.trino.spi.connector.ConnectorOutputMetadata;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableProperties;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.LimitApplicationResult;
import io.trino.spi.connector.LocalProperty;
import io.trino.spi.connector.NotFoundException;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SchemaTablePrefix;
import io.trino.spi.connector.SortingProperty;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.statistics.ComputedStatistics;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.toIntExact;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class MongoMetadata
        implements ConnectorMetadata
{
    private static final Logger log = Logger.get(MongoMetadata.class);

    private final MongoSession mongoSession;

    private final AtomicReference<Runnable> rollbackAction = new AtomicReference<>();

    public MongoMetadata(MongoSession mongoSession)
    {
        this.mongoSession = requireNonNull(mongoSession, "mongoSession is null");
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return mongoSession.getAllSchemas();
    }

    @Override
    public MongoTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        requireNonNull(tableName, "tableName is null");
        try {
            return mongoSession.getTable(tableName).getTableHandle();
        }
        catch (TableNotFoundException e) {
            log.debug(e, "Table(%s) not found", tableName);
            return null;
        }
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        requireNonNull(tableHandle, "tableHandle is null");
        SchemaTableName tableName = getTableName(tableHandle);
        return getTableMetadata(session, tableName);
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> optionalSchemaName)
    {
        List<String> schemaNames = optionalSchemaName.map(ImmutableList::of)
                .orElseGet(() -> (ImmutableList<String>) listSchemaNames(session));
        ImmutableList.Builder<SchemaTableName> tableNames = ImmutableList.builder();
        for (String schemaName : schemaNames) {
            for (String tableName : mongoSession.getAllTables(schemaName)) {
                tableNames.add(new SchemaTableName(schemaName, tableName.toLowerCase(ENGLISH)));
            }
        }
        return tableNames.build();
    }

    @Override
    public List<ColumnHandle> getColumns(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        MongoTableHandle table = (MongoTableHandle) tableHandle;
        return ImmutableList.copyOf(mongoSession.getTable(table.getSchemaTableName()).getColumns());
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        MongoTableHandle table = (MongoTableHandle) tableHandle;
        List<MongoColumnHandle> columns = mongoSession.getTable(table.getSchemaTableName()).getColumns();

        ImmutableMap.Builder<String, ColumnHandle> columnHandles = ImmutableMap.builder();
        for (MongoColumnHandle columnHandle : columns) {
            columnHandles.put(columnHandle.getName(), columnHandle);
        }
        return columnHandles.build();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        requireNonNull(prefix, "prefix is null");
        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();
        for (SchemaTableName tableName : listTables(session, prefix)) {
            try {
                columns.put(tableName, getTableMetadata(session, tableName).getColumns());
            }
            catch (NotFoundException e) {
                // table disappeared during listing operation
            }
        }
        return columns.build();
    }

    private List<SchemaTableName> listTables(ConnectorSession session, SchemaTablePrefix prefix)
    {
        if (prefix.getTable().isEmpty()) {
            return listTables(session, prefix.getSchema());
        }
        return ImmutableList.of(prefix.toSchemaTableName());
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        return ((MongoColumnHandle) columnHandle).toColumnMetadata();
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting)
    {
        mongoSession.createTable(tableMetadata.getTable(), buildColumnHandles(tableMetadata));
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        MongoTableHandle table = (MongoTableHandle) tableHandle;

        mongoSession.dropTable(table.getSchemaTableName());
    }

    @Override
    public void renameTable(ConnectorSession session, ConnectorTableHandle tableHandle, SchemaTableName newTableName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata column)
    {
        mongoSession.addColumn(((MongoTableHandle) tableHandle).getSchemaTableName(), column);
    }

    @Override
    public ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorNewTableLayout> layout)
    {
        List<MongoColumnHandle> columns = buildColumnHandles(tableMetadata);

        mongoSession.createTable(tableMetadata.getTable(), columns);

        setRollback(() -> mongoSession.dropTable(tableMetadata.getTable()));

        return new MongoOutputTableHandle(
                tableMetadata.getTable(),
                columns.stream().filter(c -> !c.isHidden()).collect(toList()));
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        clearRollback();
        return Optional.empty();
    }

    @Override
    public ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        MongoTableHandle table = (MongoTableHandle) tableHandle;
        List<MongoColumnHandle> columns = mongoSession.getTable(table.getSchemaTableName()).getColumns();

        return new MongoInsertTableHandle(
                table.getSchemaTableName(),
                columns.stream().filter(c -> !c.isHidden()).collect(toList()));
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        return Optional.empty();
    }

    @Override
    public boolean usesLegacyTableLayouts()
    {
        return false;
    }

    @Override
    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle table)
    {
        MongoTableHandle tableHandle = (MongoTableHandle) table;

        Optional<Set<ColumnHandle>> partitioningColumns = Optional.empty(); //TODO: sharding key
        ImmutableList.Builder<LocalProperty<ColumnHandle>> localProperties = ImmutableList.builder();

        MongoTable tableInfo = mongoSession.getTable(tableHandle.getSchemaTableName());
        Map<String, ColumnHandle> columns = getColumnHandles(session, tableHandle);

        for (MongoIndex index : tableInfo.getIndexes()) {
            for (MongodbIndexKey key : index.getKeys()) {
                if (key.getSortOrder().isEmpty()) {
                    continue;
                }
                if (columns.get(key.getName()) != null) {
                    localProperties.add(new SortingProperty<>(columns.get(key.getName()), key.getSortOrder().get()));
                }
            }
        }

        return new ConnectorTableProperties(
                TupleDomain.all(),
                Optional.empty(),
                partitioningColumns,
                Optional.empty(),
                localProperties.build());
    }

    @Override
    public Optional<LimitApplicationResult<ConnectorTableHandle>> applyLimit(ConnectorSession session, ConnectorTableHandle table, long limit)
    {
        MongoTableHandle handle = (MongoTableHandle) table;

        // MongoDB cursor.limit(0) is equivalent to setting no limit
        if (limit == 0) {
            return Optional.empty();
        }

        // MongoDB doesn't support limit number greater than integer max
        if (limit > Integer.MAX_VALUE) {
            return Optional.empty();
        }

        if (handle.getLimit().isPresent() && handle.getLimit().getAsInt() <= limit) {
            return Optional.empty();
        }

        return Optional.of(new LimitApplicationResult<>(
                new MongoTableHandle(handle.getSchemaTableName(), handle.getConstraint(), OptionalInt.of(toIntExact(limit))),
                true));
    }

    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session, ConnectorTableHandle table, Constraint constraint)
    {
        MongoTableHandle handle = (MongoTableHandle) table;

        TupleDomain<ColumnHandle> oldDomain = handle.getConstraint();
        TupleDomain<ColumnHandle> newDomain = oldDomain.intersect(constraint.getSummary());
        if (oldDomain.equals(newDomain)) {
            return Optional.empty();
        }

        handle = new MongoTableHandle(
                handle.getSchemaTableName(),
                newDomain,
                handle.getLimit());

        return Optional.of(new ConstraintApplicationResult<>(handle, constraint.getSummary()));
    }

    private void setRollback(Runnable action)
    {
        checkState(rollbackAction.compareAndSet(null, action), "rollback action is already set");
    }

    private void clearRollback()
    {
        rollbackAction.set(null);
    }

    public void rollback()
    {
        Optional.ofNullable(rollbackAction.getAndSet(null)).ifPresent(Runnable::run);
    }

    private static SchemaTableName getTableName(ConnectorTableHandle tableHandle)
    {
        return ((MongoTableHandle) tableHandle).getSchemaTableName();
    }

    private ConnectorTableMetadata getTableMetadata(ConnectorSession session, SchemaTableName tableName)
    {
        MongoTableHandle tableHandle = mongoSession.getTable(tableName).getTableHandle();

        List<ColumnMetadata> columns = ImmutableList.copyOf(
                getColumnHandles(session, tableHandle).values().stream()
                        .map(MongoColumnHandle.class::cast)
                        .map(MongoColumnHandle::toColumnMetadata)
                        .collect(toList()));

        return new ConnectorTableMetadata(tableName, columns);
    }

    private static List<MongoColumnHandle> buildColumnHandles(ConnectorTableMetadata tableMetadata)
    {
        return tableMetadata.getColumns().stream()
                .map(m -> new MongoColumnHandle(m.getName(), m.getType(), m.isHidden()))
                .collect(toList());
    }
}
