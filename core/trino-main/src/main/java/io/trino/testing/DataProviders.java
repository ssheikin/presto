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
package io.trino.testing;

import org.testng.annotations.DataProvider;

import java.util.stream.Collector;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

public final class DataProviders
{
    private DataProviders() {}

    public static <T> Collector<T, ?, Object[][]> toDataProvider()
    {
        return collectingAndThen(
                mapping(
                        value -> new Object[] {value},
                        toList()),
                list -> list.toArray(new Object[][] {}));
    }

    @DataProvider
    public static Object[][] trueFalse()
    {
        return new Object[][] {{true}, {false}};
    }
}
