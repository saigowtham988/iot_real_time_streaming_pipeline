# Real-Time IoT Data Streaming Pipeline

A comprehensive real-time data streaming pipeline designed to ingest, process, and visualize high-throughput IoT sensor data with sophisticated anomaly detection and real-time email alerting capabilities.

## Overview

This project demonstrates end-to-end capabilities in distributed systems, mixed-language stream processing (Python for ingestion, Java Flink for core processing), and data visualization. The pipeline simulates a real-world IoT health monitoring scenario, collecting synthetic sensor data, streaming it through an MQTT-Kafka backbone, performing real-time anomaly detection using Apache Flink, persisting data in PostgreSQL, and visualizing it in Grafana with integrated email alerts for critical events.

## Architecture

The pipeline consists of several decoupled components orchestrated using Docker Compose:

### Components

1. **IoT Sensor Simulator (Python MQTT Publisher)**
   - Generates realistic, time-series IoT sensor data from a virtual device
   - Simulates health metrics: heart rate, temperature, blood pressure, SpO2, respiration rate, blood glucose, stress level
   - Injects scheduled anomalies (fever spikes, tachycardia)
   - Publishes data to MQTT broker

2. **MQTT Broker (Mosquitto)**
   - Central messaging hub for IoT devices
   - Receives data from IoT publisher

3. **MQTT-Kafka Bridge (Python)**
   - Subscribes to MQTT topic
   - Forwards sensor data to Apache Kafka topic

4. **Apache Kafka**
   - Distributed streaming platform
   - High-throughput, fault-tolerant buffer for real-time IoT data
   - Decouples data producers from consumers

5. **Apache Flink (Java Anomaly Detection Job)**
   - Consumes messages from Kafka topic
   - Performs stateful real-time anomaly detection using predefined rules and historical trends
   - Utilizes Flink's managed state and KeyedProcessFunction
   - Integrates with JavaMail API for email alerts with per-device cooldown period
   - Persists processed data to PostgreSQL

6. **PostgreSQL Database**
   - Persistent storage for processed sensor data and detected anomalies
   - Optimized for analytical queries and visualization tools

7. **Grafana**
   - Real-time data visualization and dashboarding
   - Connects to PostgreSQL for live sensor data display
   - Highlights detected anomalies
   - Configurable email alerts

## Technologies Used

- **Messaging**: MQTT (Mosquitto), Apache Kafka
- **Stream Processing**: Apache Flink (Java)
- **Data Persistence**: PostgreSQL
- **Orchestration**: Docker Compose
- **Monitoring & Visualization**: Grafana
- **Programming Languages**: Python, Java
- **Alerting**: SMTP (Email)

## Project Structure

```
Real_Time_IoT_Data_Streaming_Pipeline/
├── README.md                           # This file
├── LICENSE                            # MIT License file
├── docker/
│   ├── docker_compose.yml             # Multi-container environment configuration
│   ├── kafka-connect-mqtt.Dockerfile  # Custom Kafka Connect image
│   ├── mqtt_source_connector.json     # MQTT Source Connector config
│   └── placeholder                    # Placeholder file
├── flink-anomaly-detector/            # Maven project for Flink job
│   ├── pom.xml                        # Maven build configuration
│   └── src/main/java/com/iot/pipeline/
│       └── AnomalyDetectionJob.java   # Flink job source code
├── grafana_dashboards/
│   └── iot_health_dashboard.json      # Grafana dashboard export
├── init/                              # Database initialization scripts
│   └── init.sql                       # PostgreSQL database initialization
├── mqtt-publisher/
│   └── iot_publisher.py               # IoT sensor data simulator
└── mqtt_kafka_bridge/
    └── mqtt_kafka_bridge.py           # MQTT to Kafka bridge
```

## Getting Started

### Prerequisites

- Docker and Docker Compose
- Maven (for building Flink job)
- Python 3.x
- Git

### Installation & Setup

1. **Clone the Repository**
   ```bash
   git clone https://github.com/your-username/my-data-engineering-portfolio.git
   cd my-data-engineering-portfolio/Real_Time_IoT_Data_Streaming_Pipeline
   ```

2. **Build Flink Job JAR**
   ```bash
   cd flink_anomaly_detector
   mvn clean package
   cd ..
   ```

3. **Configure Email Settings**
   
   **For Grafana (edit docker-compose.yml):**
   - Replace `your_strong_password` with your desired Grafana admin password
   - Replace `daafcz@gmail.com` with your Gmail address
   - Replace `hjvr bfwt hmbn uufg` with your Gmail App Password

   **For Flink Job (edit AnomalyDetectionJob.java):**
   - Replace `SENDER_EMAIL` with your Gmail address
   - Replace `SENDER_PASSWORD` with your Gmail App Password
   - Replace `RECIPIENT_EMAIL` with the alert recipient email

4. **Start All Services**
   ```bash
   docker-compose up -d
   ```

5. **Run Python Components**
   
   In separate terminals:
   
   **MQTT-Kafka Bridge:**
   ```bash
   python mqtt_kafka_bridge/mqtt_kafka_bridge.py
   ```
   
   **IoT Publisher:**
   ```bash
   python iot_publisher/iot_publisher.py
   ```

### Access Points

- **Grafana Dashboard**: [http://localhost:3000](http://localhost:3000)
  - Login: `admin` / `your_strong_password`
- **Flink UI**: [http://localhost:8081](http://localhost:8081)

## Features

- **Real-time Data Processing**: High-throughput streaming with Apache Flink
- **Anomaly Detection**: Sophisticated rule-based detection with historical trend analysis
- **Email Alerts**: Automated notifications for critical anomalies
- **Data Visualization**: Interactive Grafana dashboards
- **Scalable Architecture**: Containerized, microservices-based design
- **Fault Tolerance**: Kafka-based message buffering and Flink state management

## Configuration

The pipeline includes comprehensive configuration options for:
- MQTT broker settings
- Kafka topic configurations
- Flink job parameters
- Database connection settings
- Email alert thresholds
- Grafana dashboard layouts

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Author

**Sai Gowtham Reddy Udumula**

## Version

1.0

---

*This project showcases a robust, scalable, and fault-tolerant architecture for real-time IoT data processing, demonstrating enterprise-grade streaming capabilities with comprehensive monitoring and alerting.*
