<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
-->

# Utility Scripts

This module is a collection of few utility scripts for OpenWhisk development. The scripts
can be invoked as gradle tasks. Depending on your current directory the gradle command would
change

With current directory set to OpenWhisk home

    ./gradlew -p tools/dev <taskName>

With this module being base directory

    ../../gradlew <taskName>

## couchdbViews

Extracts and dump the design docs js in readable format. It reads all the design docs from
_<OPENWHISH_HOME>/ansibles/files_ and dumps them in _build/views_ directory

Sample output

    $./gradlew -p tools/dev couchdbViews
    Processing whisks_design_document_for_entities_db_v2.1.0.json
            - whisks.v2.1.0-rules.js
            - whisks.v2.1.0-packages-public.js
            - whisks.v2.1.0-packages.js
            - whisks.v2.1.0-actions.js
            - whisks.v2.1.0-triggers.js
    Processing activations_design_document_for_activations_db.json
            - activations-byDate.js
    Processing auth_index.json
            - subjects-identities.js
    Processing filter_design_document.json
    Processing whisks_design_document_for_activations_db_v2.1.0.json
            - whisks.v2.1.0-activations.js
    Skipping runtimes.json
    Processing logCleanup_design_document_for_activations_db.json
            - logCleanup-byDateWithLogs.js
    Processing whisks_design_document_for_all_entities_db_v2.1.0.json
            - all-whisks.v2.1.0-all.js
    Processing whisks_design_document_for_activations_db_filters_v2.1.0.json
            - whisks-filters.v2.1.0-activations.js
    Generated view json files in /path/too/tools/build/views

## IntelliJ Run Config Generator

This script enables creation of [Intellij Launch Configuration][1] in _<openwhisk home>/.idea/runConfigurations_
with name controller0 and invoker0. For this to work your Intellij project should be [directory based][3]. If your
project is file based (uses ipr files) then you can convert it to directory based via _File -> Save as Directory-Based Format_. These run configurations can then be invoked from _Run -> Edit Configurations -> Application_

### Usage

First setup OpenWhisk so that Controller and Invoker containers are up and running. Then run the script:

    ./gradlew -p tools/dev intellij

It would inspect the running docker containers and then generate the launch configs with name 'controller0'
and 'invoker0'.

Key points to note:

1. Uses ~/tmp/openwhisk/controller (or invoker) as working directory.
2. Changes the PORT to linked one. So controller gets started at 10001 only just like as its done in container.

Now the docker container can be stopped and application can be launched from within the IDE.

**Note** - Currently only the controller can be run from IDE. Invoker posses some [problems][2].

### Configuration

The script allows some local customization of the launch configuration. This can be done by creating a [config][4] file
`intellij-run-config.groovy` in project root directory. Below is an example of _<openwhisk home>/intellij-run-config.groovy_
file to customize the logging and db port used for CouchDB.

```groovy
//Configures the settings for controller application
controller {
    //Base directory used for controller process
    workingDir = "/path/to/controller"
    //System properties to be set
    props = [
            'logback.configurationFile':'/path/to/custom/logback.xml'
    ]
    //Environment variables to be set
    env = [
            'DB_PORT' : '5989',
            'CONFIG_whisk_controller_protocol' : 'http'
    ]
}

invoker {
    workingDir = "/path/to/invoker"
    props = [
            'logback.configurationFile':'/path/to/custom/logback.xml'
    ]
    env = [
            'DB_PORT' : '5989'
    ]
}

```

The config allows following properties:

* `workingDir` - Base directory used for controller or invoker process.
* `props` - Map of system properties which should be passed to the application.
* `env` - Map of environment variables which should be set for application process.

[1]: https://www.jetbrains.com/help/idea/run-debug-configurations-dialog.html#run_config_common_options
[2]: https://github.com/apache/incubator-openwhisk/issues/3195
[3]: https://www.jetbrains.com/help/idea/configuring-projects.html#project-formats
[4]: http://docs.groovy-lang.org/2.4.2/html/gapi/groovy/util/ConfigSlurper.html
