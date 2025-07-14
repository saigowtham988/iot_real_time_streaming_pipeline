"""
MQTT to Kafka Bridge

This script acts as a bridge between an MQTT broker and an Apache Kafka cluster.
It subscribes to a specified MQTT topic, receives messages, and then publishes
these messages to a designated Kafka topic. This component is crucial for
ingesting real-time IoT data from MQTT devices into a Kafka-based streaming pipeline.

Components:
- MQTT Client: Connects to the MQTT broker and subscribes to a topic.
- Kafka Producer: Connects to the Kafka cluster and publishes messages.
- Message Handling: Parses JSON payloads from MQTT and sends them to Kafka.

Configuration:
- MQTT_BROKER: Hostname or IP of the MQTT broker.
- MQTT_PORT: Port of the MQTT broker.
- MQTT_TOPIC: MQTT topic to subscribe to.
- KAFKA_BROKER: Hostname and port of the Kafka broker.
- KAFKA_TOPIC: Kafka topic to publish messages to.

Author: Sai Gowtham Reddy Udumula
"""

import paho.mqtt.client as mqtt
from kafka import KafkaProducer
import json
import time
from typing import Any, Dict

# --- Configuration ---
MQTT_BROKER = "localhost" # Mosquitto running on host, mapped to Docker
MQTT_PORT = 1883
MQTT_TOPIC = "iot/sensor_data"

KAFKA_BROKER = "localhost:9092" # Kafka running on host, mapped to Docker
KAFKA_TOPIC = "iot_raw_data"

# --- Kafka Producer Setup ---
producer = None
try:
    producer = KafkaProducer(
        bootstrap_servers=[KAFKA_BROKER],
        value_serializer=lambda v: json.dumps(v).encode('utf-8'),
        api_version=(0, 10) # Set API version for broader compatibility
    )
    print(f"Successfully connected to Kafka broker: {KAFKA_BROKER}")
except Exception as e:
    print(f"Error connecting to Kafka: {e}")
    print("Please ensure Kafka is running and accessible at 'localhost:9092'.")
    exit(1)

# --- MQTT Callbacks ---
def on_connect(client: mqtt.Client, userdata: Any, flags: Dict[str, Any], rc: int):
    """Callback for when the MQTT client connects to the broker."""
    if rc == 0:
        print("Connected to MQTT Broker!")
        client.subscribe(MQTT_TOPIC)
        print(f"Subscribed to MQTT topic: {MQTT_TOPIC}")
    else:
        print(f"Failed to connect to MQTT broker, return code {rc}")
        exit(1)

def on_message(client: mqtt.Client, userdata: Any, msg: mqtt.MQTTMessage):
    """Callback for when a message is received from the MQTT broker."""
    print(f"Received MQTT message on topic '{msg.topic}': {msg.payload.decode()}")
    try:
        # Parse the JSON payload
        data = json.loads(msg.payload.decode('utf-8'))

        # Send to Kafka
        # The .get(timeout=...) is important for blocking until delivery confirmation
        # or timeout, helping to ensure message delivery before moving on.
        future = producer.send(KAFKA_TOPIC, data)
        record_metadata = future.get(timeout=10) # Wait for 10 seconds for delivery confirmation
        print(f"Successfully sent to Kafka topic '{record_metadata.topic}' partition {record_metadata.partition} offset {record_metadata.offset}")

    except json.JSONDecodeError:
        print(f"Error: Could not decode JSON from MQTT message: {msg.payload.decode()}")
    except Exception as e:
        print(f"Error sending message to Kafka: {e}")
        # In a production environment, consider implementing retry logic or dead-letter queues.

# --- Main Bridge Logic ---
print("Starting MQTT to Kafka Bridge...")
mqtt_client = mqtt.Client()
mqtt_client.on_connect = on_connect
mqtt_client.on_message = on_message

try:
    mqtt_client.connect(MQTT_BROKER, MQTT_PORT, 60) # Connect with a 60-second keepalive
    mqtt_client.loop_forever() # Blocks and handles network traffic, callbacks
except KeyboardInterrupt:
    print("\nStopping MQTT to Kafka Bridge due to user interrupt (Ctrl+C).")
except Exception as e:
    print(f"An unexpected error occurred: {e}")
finally:
    if producer:
        producer.close() # Close Kafka producer gracefully
        print("Kafka producer closed.")
    mqtt_client.disconnect() # Disconnect MQTT client gracefully
    print("MQTT client disconnected. Exiting.")
