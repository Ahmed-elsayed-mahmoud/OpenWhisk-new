<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements.  See the NOTICE file distributed with this work for additional
# information regarding copyright ownership.  The ASF licenses this file to you
# under the Apache License, Version 2.0 (the # "License"); you may not use this
# file except in compliance with the License.  You may obtain a copy of the License
# at:
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations under the License.
#
-->
# Openwhisk Metric Support

Openwhick contains the capability to send metric information to a statsd server. This capability is disabled per default. Instead metric information is normally written to the log files in logmarker format.

## Configuration

Both capabilties can be enabled or disabled separately during deployment via Ansible configuration in the 'goup_vars/all' file of an  environment.

There are four configurations options available:

- **metrics_log** [true / false  (default: true)]

  Enable/disable whether the metric information is written out to the log files in logmarker format.

  *Beware: Even if set to false all messages adjourning the log markers are still written out to the log*

- **metrics_kamon** [true / false (default: false)]

  Enable/disable whther metric information is send the configured statsd server.

- **metrics_kamon_tags: false** [true / false  (default: false)]

  Enable/disable whether to use the Kamon tags when sending metrics.

  *Notice: Tag is supported in some kamon-backend. (OpenTSDB, Datadog, InfluxDB)*

- **metrics_kamon_statsd_host** [hostname or ip address]

  Hostname or ip address of the statsd server

- **metrics_kamon_statsd_port** [port number (default:8125)]

  Port number of the statsd server


Example configuration:

```
metrics_kamon: true
metrics_kamon_tags: false
metrics_kamon_statsd_host: '192.168.99.100'
metrics_kamon_statsd_port: '8125'
metrics_log: true
```

## Testing the statsd metric support

The Kamon project privides an integrated docker image containing statsd and a connected Grafana dashboard via [this Github project](https://github.com/kamon-io/docker-grafana-graphite). This image is helpful for testing the metrices sent via statsd.

Please follow these [instructions](https://github.com/kamon-io/docker-grafana-graphite/blob/master/README.md) to start the docker image in your local docker environment.

The docker image exposes statsd via the (standard) port 8125 and a Graphana dashboard via port 8080 on your docker host.

The address of your docker host has to be configured in the `metrics_kamon_statsd_host` configuration property.
