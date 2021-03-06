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
package io.trino.execution;

import com.google.common.util.concurrent.ListenableFuture;
import io.trino.execution.warnings.WarningCollector;
import io.trino.metadata.Metadata;
import io.trino.security.AccessControl;
import io.trino.sql.tree.Deallocate;
import io.trino.sql.tree.Expression;
import io.trino.transaction.TransactionManager;

import java.util.List;

import static com.google.common.util.concurrent.Futures.immediateFuture;

public class DeallocateTask
        implements DataDefinitionTask<Deallocate>
{
    @Override
    public String getName()
    {
        return "DEALLOCATE";
    }

    @Override
    public ListenableFuture<?> execute(
            Deallocate statement,
            TransactionManager transactionManager,
            Metadata metadata,
            AccessControl accessControl,
            QueryStateMachine stateMachine,
            List<Expression> parameters,
            WarningCollector warningCollector)
    {
        String statementName = statement.getName().getValue();
        stateMachine.removePreparedStatement(statementName);
        return immediateFuture(null);
    }
}
