FROM confluentinc/cp-kafka-connect:7.5.3

# Install the MQTT connector
RUN confluent-hub install --no-prompt confluentinc/kafka-connect-mqtt:latest \
    && sync \
    && /usr/bin/chmod -R ug+rwX /usr/share/confluent-hub-components/confluentinc-kafka-connect-mqtt \
    && /usr/bin/chown -R appuser:appuser /usr/share/confluent-hub-components/confluentinc-kafka-connect-mqtt

# Verify files are there (optional, but good for future debugging)
RUN ls -l /usr/share/confluent-hub-components/confluentinc-kafka-connect-mqtt/lib/

# IMPORTANT: Set the plugin path to include all standard locations
# This covers cases where the base image might have its own default paths,
# and ensures the specific connector directory is also directly scanned.
ENV CONNECT_PLUGIN_PATH /usr/share/java,/usr/share/confluent-hub-components,/usr/share/confluent-hub-components/confluentinc-kafka-connect-mqtt