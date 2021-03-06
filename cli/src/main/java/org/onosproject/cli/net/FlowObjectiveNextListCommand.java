/*
 * Copyright 2016-present Open Networking Foundation
 *
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
package org.onosproject.cli.net;

import java.util.List;
import org.onlab.osgi.ServiceNotFoundException;

import org.apache.karaf.shell.commands.Command;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.flowobjective.FlowObjectiveService;

/**
 * Returns a mapping of FlowObjective next-ids to the groups that get created
 * by a device driver. These mappings are controller instance specific.
 */
@Command(scope = "onos", name = "obj-next-ids",
        description = "flow-objectives next-ids to group-ids mapping")
public class FlowObjectiveNextListCommand extends AbstractShellCommand {

    private static final String FORMAT_MAPPING = "  %s";

    @Override
    protected void execute() {
        try {
            FlowObjectiveService service = get(FlowObjectiveService.class);
            printNexts(service.getNextMappings());
        } catch (ServiceNotFoundException e) {
            print(FORMAT_MAPPING, "FlowObjectiveService unavailable");
        }
    }

    private void printNexts(List<String> nextGroupMappings) {
        nextGroupMappings.forEach(str -> print(FORMAT_MAPPING, str));
    }
}
