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
package io.github.baptistegh.trino.scim;

import io.airlift.log.Logger;
import org.weakref.jmx.MBeanExporter;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import java.util.Set;

import static org.weakref.jmx.ObjectNames.generatedNameOf;

/**
 * Exports the metrics of the plugin to JMX, where Trino's JMX connector and JMX exporters can read them.
 */
final class ScimMBeans
{
    private static final Logger log = Logger.get(ScimMBeans.class);

    private ScimMBeans() {}

    /**
     * Exports each object under the name generated from its class, for example
     * {@code io.github.baptistegh.trino.scim:name=ScimClient}. An MBean already registered under that name,
     * from a group provider created earlier in the same JVM, is replaced. A failure is logged rather than
     * thrown: metrics are not worth failing the group provider for.
     */
    static void export(MBeanServer mbeanServer, Set<Object> objects)
    {
        MBeanExporter exporter = new MBeanExporter(mbeanServer);
        for (Object object : objects) {
            String name = generatedNameOf(object.getClass());
            try {
                ObjectName objectName = new ObjectName(name);
                if (mbeanServer.isRegistered(objectName)) {
                    mbeanServer.unregisterMBean(objectName);
                }
                exporter.export(objectName, object);
            }
            catch (JMException | RuntimeException e) {
                log.warn(e, "Failed to export MBean %s", name);
            }
        }
    }
}
