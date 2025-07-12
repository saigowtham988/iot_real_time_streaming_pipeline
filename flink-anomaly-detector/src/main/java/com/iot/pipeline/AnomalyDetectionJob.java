/**
 * Apache Flink IoT Anomaly Detection Job
 *
 * This Flink job implements a real-time stream processing pipeline for IoT sensor data.
 * It consumes data from Apache Kafka, performs stateful anomaly detection per device,
 * and then persists the processed data (including anomaly flags) into a PostgreSQL database.
 * Additionally, it integrates with the JavaMail API to send email alerts for critical anomalies.
 *
 * This project showcases expertise in:
 * - **Real-time Stream Processing:** Utilizing Apache Flink for high-throughput, low-latency data analysis.
 * - **Stateful Computations:** Managing per-device historical data using Flink's managed state for trend analysis.
 * - **Anomaly Detection:** Implementing both rule-based and simple trend-based algorithms for health metrics.
 * - **Distributed Systems:** Integration with Kafka (messaging backbone) and PostgreSQL (persistent storage).
 * - **Fault Tolerance:** Leveraging Flink's checkpointing mechanism for reliable data processing.
 * - **External System Integration:** Connecting to Kafka as a source, PostgreSQL as a sink, and an SMTP server for alerts.
 *
 * Components:
 * - **Kafka Source:** Reads JSON-formatted sensor data from the `iot_raw_data` Kafka topic.
 * - **JsonParser (RichMapFunction):** Deserializes incoming JSON strings into structured `IoTRecord` objects.
 * - **AnomalyDetectionFunction (KeyedProcessFunction):**
 * - Keys the stream by `device_id` to maintain independent state for each device.
 * - Stores historical sensor readings in `ValueState` (e.g., `heartRateHistory`, `temperatureHistory`).
 * - Applies predefined rule-based thresholds (e.g., heart rate out of range, high temperature).
 * - Detects trends (e.g., sudden spikes or drops from recent averages) using historical data.
 * - Flags records as anomalous (`isAnomaly` boolean).
 * - Triggers email alerts for detected anomalies, respecting a per-device cooldown period.
 * - **PostgreSQL Sink (JdbcSink):** Batches and writes processed `IoTRecord` data into the `anomalies` table in PostgreSQL.
 *
 * Configuration:
 * - Kafka Broker: `kafka:9092` (configured for a Docker Compose environment).
 * - Kafka Topic: `iot_raw_data`.
 * - PostgreSQL JDBC URL: `jdbc:postgresql://postgres:5432/iot_db` (for a Docker Compose environment).
 * - Email SMTP details: Configured within `AnomalyDetectionFunction` (placeholders must be replaced with actual credentials).
 *
 * To build and run this job:
 * 1. Ensure Java Development Kit (JDK) 11 or higher and Apache Maven are installed.
 * 2. Compile the project into a fat JAR using Maven: `mvn clean package`.
 * 3. Submit the generated JAR to a running Apache Flink cluster.
 *
 * Author: Sai Gowtham reddy Udumula
 */
package com.iot.pipeline;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.jdbc.JdbcStatementBuilder;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;

import javax.mail.*;
import javax.mail.internet.*;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AnomalyDetectionJob {
    
    public static void main(String[] args) throws Exception {
        // Set up the streaming execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Enable checkpointing for fault tolerance (every 5 seconds)
        env.enableCheckpointing(5000);
        
        // Configure Kafka source
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers("kafka:9092") // Kafka broker address
                .setTopics("iot_raw_data") // Kafka topic to consume from
                .setGroupId("flink-anomaly-detector") // Consumer group ID
                .setStartingOffsets(OffsetsInitializer.earliest()) // Start reading from the beginning of the topic
                .setValueOnlyDeserializer(new SimpleStringSchema()) // Deserializer for string values
                .build();
        
        // Create data stream from Kafka
        DataStream<String> kafkaStream = env.fromSource(source, 
                WatermarkStrategy.noWatermarks(), "Kafka Source"); // No watermarks for simplicity, can be added for event-time processing
        
        // Parse JSON and detect anomalies
        DataStream<IoTRecord> parsedStream = kafkaStream
                .map(new JsonParser()) // Parse incoming JSON strings into IoTRecord objects
                .keyBy(IoTRecord::getDeviceId) // Key the stream by device_id for stateful processing per device
                .process(new AnomalyDetectionFunction()); // Apply anomaly detection logic
        
        // Sink the processed data to PostgreSQL
        parsedStream.addSink(createPostgresSink());
        
        // Execute the job with a given name
        env.execute("IoT Anomaly Detection Job");
    }
    
    // JSON Parser Function: Converts incoming JSON strings from Kafka into IoTRecord objects.
    public static class JsonParser extends RichMapFunction<String, IoTRecord> {
        private ObjectMapper objectMapper; // Jackson ObjectMapper for JSON parsing
        
        @Override
        public void open(Configuration parameters) {
            objectMapper = new ObjectMapper(); // Initialize ObjectMapper
        }
        
        @Override
        public IoTRecord map(String value) throws Exception {
            JsonNode jsonNode = objectMapper.readTree(value); // Parse JSON string into a JsonNode tree
            return new IoTRecord(
                jsonNode.get("device_id").asText(),
                jsonNode.get("timestamp").asText(),
                jsonNode.has("heart_rate") ? jsonNode.get("heart_rate").asDouble() : null,
                jsonNode.has("temperature") ? jsonNode.get("temperature").asDouble() : null,
                jsonNode.has("systolic_pressure") ? jsonNode.get("systolic_pressure").asDouble() : null,
                jsonNode.has("diastolic_pressure") ? jsonNode.get("diastolic_pressure").asDouble() : null,
                jsonNode.has("oxygen_saturation") ? jsonNode.get("oxygen_saturation").asDouble() : null,
                jsonNode.has("respiration_rate") ? jsonNode.get("respiration_rate").asDouble() : null,
                jsonNode.has("blood_glucose") ? jsonNode.get("blood_glucose").asDouble() : null,
                jsonNode.has("steps_count") ? jsonNode.get("steps_count").asInt() : null,
                jsonNode.has("calories_burned") ? jsonNode.get("calories_burned").asDouble() : null,
                jsonNode.has("stress_level") ? jsonNode.get("stress_level").asDouble() : null
            );
        }
    }
    
    // Anomaly Detection Function with Flink's Managed State
    public static class AnomalyDetectionFunction extends KeyedProcessFunction<String, IoTRecord, IoTRecord> {
        
        // Flink ValueState to store historical data for each keyed device.
        // Using LinkedList as a Queue implementation for fixed-size history window.
        private ValueState<Queue<Double>> heartRateHistory;
        private ValueState<Queue<Double>> temperatureHistory;
        private ValueState<Queue<Double>> systolicPressureHistory;
        private ValueState<Queue<Double>> diastolicPressureHistory;
        private ValueState<Queue<Double>> oxygenSaturationHistory;
        private ValueState<Queue<Double>> respirationRateHistory;
        private ValueState<Queue<Double>> bloodGlucoseHistory;
        private ValueState<Queue<Double>> stressLevelHistory;
        
        // Flink ValueState for email cooldown tracking per device.
        private ValueState<Long> lastEmailSentTime;
        
        // Configuration constants for anomaly detection and email
        private static final int HISTORY_WINDOW_SIZE = 10; // Number of past readings to maintain
        private static final long EMAIL_COOLDOWN_SECONDS = 60; // 1 minute cooldown between emails for a device
        
        // Threshold configurations for rule-based anomaly detection
        private static final double[] HEART_RATE_RANGE = {50.0, 120.0}; // bpm
        private static final double[] TEMP_RANGE = {35.0, 39.5}; // °C
        private static final double[] SYSTOLIC_BP_RANGE = {80.0, 150.0}; // mmHg
        private static final double[] DIASTOLIC_BP_RANGE = {40.0, 100.0}; // mmHg
        private static final double[] SPO2_RANGE = {90.0, 100.0}; // % (below 90 is concerning)
        private static final double[] RESPIRATION_RATE_RANGE = {10.0, 25.0}; // breaths/min
        private static final double[] BLOOD_GLUCOSE_RANGE = {60.0, 180.0}; // mg/dL
        private static final double STRESS_LEVEL_HIGH_THRESHOLD = 6.0; // Out of 10
        
        // Thresholds for trend-based anomaly detection (percentage/absolute change from average in window)
        private static final double HEART_RATE_SPIKE_THRESHOLD_PERCENT = 0.15; // 15% increase from average
        private static final double TEMP_SPIKE_THRESHOLD_CELSIUS = 1.0; // 1.0 degree C increase from average
        private static final double PRESSURE_SPIKE_THRESHOLD_PERCENT = 0.10; // 10% increase from average
        private static final double SPO2_DROP_THRESHOLD_PERCENT = 0.03; // 3% drop from average
        private static final double RESPIRATION_RATE_SPIKE_THRESHOLD = 5.0; // 5 breaths/min increase from average
        private static final double BLOOD_GLUCOSE_SPIKE_THRESHOLD = 30.0; // mg/dL increase from average
        private static final double STRESS_LEVEL_SPIKE_THRESHOLD = 3.0; // points increase from average
        
        // Email configuration (replace with actual sender/recipient details and app password)
        private static final String SMTP_SERVER = "smtp.gmail.com";
        private static final String SMTP_PORT = "587";
        private static final String SENDER_EMAIL = "your-email@gmail.com"; // Replace with your Gmail address
        private static final String SENDER_PASSWORD = "your-app-password"; // Replace with your Gmail App Password
        private static final String RECIPIENT_EMAIL = "recipient@gmail.com"; // Replace with recipient email
        
        @Override
        @SuppressWarnings("unchecked") // Suppress unchecked cast warning for Queue
        public void open(Configuration parameters) {
            // Initialize Flink's managed state. State is fault-tolerant and automatically checkpointed.
            heartRateHistory = getRuntimeContext().getState(new ValueStateDescriptor<>("heartRateHistory", 
                    (Class<Queue<Double>>) (Class<?>) LinkedList.class)); // Cast to LinkedList as concrete Queue impl
            temperatureHistory = getRuntimeContext().getState(new ValueStateDescriptor<>("temperatureHistory", 
                    (Class<Queue<Double>>) (Class<?>) LinkedList.class));
            systolicPressureHistory = getRuntimeContext().getState(new ValueStateDescriptor<>("systolicPressureHistory", 
                    (Class<Queue<Double>>) (Class<?>) LinkedList.class));
            diastolicPressureHistory = getRuntimeContext().getState(new ValueStateDescriptor<>("diastolicPressureHistory", 
                    (Class<Queue<Double>>) (Class<?>) LinkedList.class));
            oxygenSaturationHistory = getRuntimeContext().getState(new ValueStateDescriptor<>("oxygenSaturationHistory", 
                    (Class<Queue<Double>>) (Class<?>) LinkedList.class));
            respirationRateHistory = getRuntimeContext().getState(new ValueStateDescriptor<>("respirationRateHistory", 
                    (Class<Queue<Double>>) (Class<?>) LinkedList.class));
            bloodGlucoseHistory = getRuntimeContext().getState(new ValueStateDescriptor<>("bloodGlucoseHistory", 
                    (Class<Queue<Double>>) (Class<?>) LinkedList.class));
            stressLevelHistory = getRuntimeContext().getState(new ValueStateDescriptor<>("stressLevelHistory", 
                    (Class<Queue<Double>>) (Class<?>) LinkedList.class));
            
            lastEmailSentTime = getRuntimeContext().getState(new ValueStateDescriptor<>("lastEmailSentTime", Long.class));
        }
        
        @Override
        public void processElement(IoTRecord record, Context context, Collector<IoTRecord> out) throws Exception {
            boolean isAnomaly = false;
            List<String> alertMessages = new ArrayList<>();
            
            // Initialize histories if null
            initializeHistories();
            
            // Update histories with current values
            updateHistories(record);
            
            // Rule-based anomaly detection
            isAnomaly |= detectRuleBasedAnomalies(record, alertMessages);
            
            // Trend-based anomaly detection
            isAnomaly |= detectTrendBasedAnomalies(record, alertMessages);
            
            // Set anomaly status
            record.setAnomaly(isAnomaly);
            
            // Send email if anomaly detected and cooldown period has passed
            if (isAnomaly && shouldSendEmail()) {
                sendEmailAlert(record, alertMessages);
                lastEmailSentTime.update(System.currentTimeMillis());
            }
            
            // Output the record
            out.collect(record);
        }
        
        private void initializeHistories() throws Exception {
            // Check if any history state is null, if so, initialize all of them
            if (heartRateHistory.value() == null) {
                heartRateHistory.update(new LinkedList<>());
                temperatureHistory.update(new LinkedList<>());
                systolicPressureHistory.update(new LinkedList<>());
                diastolicPressureHistory.update(new LinkedList<>());
                oxygenSaturationHistory.update(new LinkedList<>());
                respirationRateHistory.update(new LinkedList<>());
                bloodGlucoseHistory.update(new LinkedList<>());
                stressLevelHistory.update(new LinkedList<>());
            }
        }
        
        private void updateHistories(IoTRecord record) throws Exception {
            // Add current sensor values to their respective history queues
            updateHistory(heartRateHistory, record.getHeartRate());
            updateHistory(temperatureHistory, record.getTemperature());
            updateHistory(systolicPressureHistory, record.getSystolicPressure());
            updateHistory(diastolicPressureHistory, record.getDiastolicPressure());
            updateHistory(oxygenSaturationHistory, record.getOxygenSaturation());
            updateHistory(respirationRateHistory, record.getRespirationRate());
            updateHistory(bloodGlucoseHistory, record.getBloodGlucose());
            updateHistory(stressLevelHistory, record.getStressLevel());
        }
        
        private void updateHistory(ValueState<Queue<Double>> historyState, Double value) throws Exception {
            if (value != null) {
                Queue<Double> history = historyState.value();
                history.offer(value); // Add new value to the end of the queue
                if (history.size() > HISTORY_WINDOW_SIZE) {
                    history.poll(); // Remove oldest value if window size is exceeded
                }
                historyState.update(history); // Update the Flink state
            }
        }
        
        private boolean detectRuleBasedAnomalies(IoTRecord record, List<String> alertMessages) {
            boolean anomaly = false;
            
            // Rule-based checks for each sensor metric
            if (record.getHeartRate() != null && 
                (record.getHeartRate() < HEART_RATE_RANGE[0] || record.getHeartRate() > HEART_RATE_RANGE[1])) {
                anomaly = true;
                alertMessages.add(String.format("Heart rate of %.1f bpm is outside normal range (%.1f-%.1f).", 
                                record.getHeartRate(), HEART_RATE_RANGE[0], HEART_RATE_RANGE[1]));
            }
            
            if (record.getTemperature() != null && 
                (record.getTemperature() < TEMP_RANGE[0] || record.getTemperature() > TEMP_RANGE[1])) {
                anomaly = true;
                alertMessages.add(String.format("Temperature of %.1f°C is outside normal range (%.1f-%.1f).", 
                                record.getTemperature(), TEMP_RANGE[0], TEMP_RANGE[1]));
            }
            
            if (record.getSystolicPressure() != null && 
                (record.getSystolicPressure() < SYSTOLIC_BP_RANGE[0] || record.getSystolicPressure() > SYSTOLIC_BP_RANGE[1])) {
                anomaly = true;
                alertMessages.add(String.format("Systolic pressure of %.1f mmHg is outside normal range (%.1f-%.1f).", 
                                record.getSystolicPressure(), SYSTOLIC_BP_RANGE[0], SYSTOLIC_BP_RANGE[1]));
            }
            
            if (record.getDiastolicPressure() != null && 
                (record.getDiastolicPressure() < DIASTOLIC_BP_RANGE[0] || record.getDiastolicPressure() > DIASTOLIC_BP_RANGE[1])) {
                anomaly = true;
                alertMessages.add(String.format("Diastolic pressure of %.1f mmHg is outside normal range (%.1f-%.1f).", 
                                record.getDiastolicPressure(), DIASTOLIC_BP_RANGE[0], DIASTOLIC_BP_RANGE[1]));
            }
            
            if (record.getOxygenSaturation() != null && 
                (record.getOxygenSaturation() < SPO2_RANGE[0] || record.getOxygenSaturation() > SPO2_RANGE[1])) {
                anomaly = true;
                alertMessages.add(String.format("Oxygen saturation of %.1f%% is outside normal range (%.1f-%.1f).", 
                                record.getOxygenSaturation(), SPO2_RANGE[0], SPO2_RANGE[1]));
            }
            
            if (record.getRespirationRate() != null && 
                (record.getRespirationRate() < RESPIRATION_RATE_RANGE[0] || record.getRespirationRate() > RESPIRATION_RATE_RANGE[1])) {
                anomaly = true;
                alertMessages.add(String.format("Respiration rate of %.1f breaths/min is outside normal range (%.1f-%.1f).", 
                                record.getRespirationRate(), RESPIRATION_RATE_RANGE[0], RESPIRATION_RATE_RANGE[1]));
            }
            
            if (record.getBloodGlucose() != null && 
                (record.getBloodGlucose() < BLOOD_GLUCOSE_RANGE[0] || record.getBloodGlucose() > BLOOD_GLUCOSE_RANGE[1])) {
                anomaly = true;
                alertMessages.add(String.format("Blood glucose of %.1f mg/dL is outside normal range (%.1f-%.1f).", 
                                record.getBloodGlucose(), BLOOD_GLUCOSE_RANGE[0], BLOOD_GLUCOSE_RANGE[1]));
            }
            
            if (record.getStressLevel() != null && record.getStressLevel() > STRESS_LEVEL_HIGH_THRESHOLD) {
                anomaly = true;
                alertMessages.add(String.format("Stress level of %.1f is unusually high (>%.1f).", 
                                record.getStressLevel(), STRESS_LEVEL_HIGH_THRESHOLD));
            }
            
            return anomaly;
        }
        
        private boolean detectTrendBasedAnomalies(IoTRecord record, List<String> alertMessages) throws Exception {
            boolean anomaly = false;
            
            // Heart rate trend
            if (record.getHeartRate() != null && heartRateHistory.value().size() == HISTORY_WINDOW_SIZE) {
                double avg = calculateAverage(heartRateHistory.value());
                if (record.getHeartRate() > avg * (1 + HEART_RATE_SPIKE_THRESHOLD_PERCENT)) {
                    anomaly = true;
                    alertMessages.add(String.format("Gradual heart rate increase detected! Current: %.1f bpm, Average: %.1f bpm.", 
                                    record.getHeartRate(), avg));
                }
            }
            
            // Temperature trend
            if (record.getTemperature() != null && temperatureHistory.value().size() == HISTORY_WINDOW_SIZE) {
                double avg = calculateAverage(temperatureHistory.value());
                if (record.getTemperature() > avg + TEMP_SPIKE_THRESHOLD_CELSIUS) {
                    anomaly = true;
                    alertMessages.add(String.format("Gradual temperature increase detected! Current: %.1f°C, Average: %.1f°C.", 
                                    record.getTemperature(), avg));
                }
            }
            
            // Systolic pressure trend
            if (record.getSystolicPressure() != null && systolicPressureHistory.value().size() == HISTORY_WINDOW_SIZE) {
                double avg = calculateAverage(systolicPressureHistory.value());
                if (record.getSystolicPressure() > avg * (1 + PRESSURE_SPIKE_THRESHOLD_PERCENT)) {
                    anomaly = true;
                    alertMessages.add(String.format("Gradual systolic pressure increase detected! Current: %.1f mmHg, Average: %.1f mmHg.", 
                                    record.getSystolicPressure(), avg));
                }
            }
            
            // Diastolic pressure trend
            if (record.getDiastolicPressure() != null && diastolicPressureHistory.value().size() == HISTORY_WINDOW_SIZE) {
                double avg = calculateAverage(diastolicPressureHistory.value());
                if (record.getDiastolicPressure() > avg * (1 + PRESSURE_SPIKE_THRESHOLD_PERCENT)) {
                    anomaly = true;
                    alertMessages.add(String.format("Gradual diastolic pressure increase detected! Current: %.1f mmHg, Average: %.1f mmHg.", 
                                    record.getDiastolicPressure(), avg));
                }
            }
            
            // Oxygen saturation trend (drop detection)
            if (record.getOxygenSaturation() != null && oxygenSaturationHistory.value().size() == HISTORY_WINDOW_SIZE) {
                double avg = calculateAverage(oxygenSaturationHistory.value());
                if (record.getOxygenSaturation() < avg * (1 - SPO2_DROP_THRESHOLD_PERCENT)) {
                    anomaly = true;
                    alertMessages.add(String.format("Gradual oxygen saturation drop detected! Current: %.1f%%, Average: %.1f%%.", 
                                    record.getOxygenSaturation(), avg));
                }
            }
            
            // Respiration rate trend
            if (record.getRespirationRate() != null && respirationRateHistory.value().size() == HISTORY_WINDOW_SIZE) {
                double avg = calculateAverage(respirationRateHistory.value());
                if (record.getRespirationRate() > avg + RESPIRATION_RATE_SPIKE_THRESHOLD) {
                    anomaly = true;
                    alertMessages.add(String.format("Gradual respiration rate increase detected! Current: %.1f breaths/min, Average: %.1f breaths/min.", 
                                    record.getRespirationRate(), avg));
                }
            }
            
            // Blood glucose trend
            if (record.getBloodGlucose() != null && bloodGlucoseHistory.value().size() == HISTORY_WINDOW_SIZE) {
                double avg = calculateAverage(bloodGlucoseHistory.value());
                if (record.getBloodGlucose() > avg + BLOOD_GLUCOSE_SPIKE_THRESHOLD) {
                    anomaly = true;
                    alertMessages.add(String.format("Gradual blood glucose increase detected! Current: %.1f mg/dL, Average: %.1f mg/dL.", 
                                    record.getBloodGlucose(), avg));
                }
            }
            
            // Stress level trend
            if (record.getStressLevel() != null && stressLevelHistory.value().size() == HISTORY_WINDOW_SIZE) {
                double avg = calculateAverage(stressLevelHistory.value());
                if (record.getStressLevel() > avg + STRESS_LEVEL_SPIKE_THRESHOLD) {
                    anomaly = true;
                    alertMessages.add(String.format("Gradual stress level increase detected! Current: %.1f, Average: %.1f.", 
                                    record.getStressLevel(), avg));
                }
            }
            
            return anomaly;
        }
        
        private double calculateAverage(Queue<Double> values) {
            // Calculates the average of values in a queue. Returns 0.0 if queue is empty.
            return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
        
        private boolean shouldSendEmail() throws Exception {
            // Check if enough time has passed since the last email alert for this device.
            Long lastSent = lastEmailSentTime.value();
            if (lastSent == null) {
                return true; // No email sent yet, so send it.
            }
            return (System.currentTimeMillis() - lastSent) > (EMAIL_COOLDOWN_SECONDS * 1000);
        }
        
        private void sendEmailAlert(IoTRecord record, List<String> alertMessages) {
            // Sends an email alert using JavaMail API.
            try {
                Properties props = new Properties();
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host", SMTP_SERVER);
                props.put("mail.smtp.port", SMTP_PORT);
                
                // Authenticate with sender's email and app password
                Session session = Session.getInstance(props, new javax.mail.Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(SENDER_EMAIL, SENDER_PASSWORD);
                    }
                });
                
                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress(SENDER_EMAIL));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(RECIPIENT_EMAIL));
                message.setSubject("Urgent Health Alert for " + record.getDeviceId());
                
                StringBuilder body = new StringBuilder();
                body.append("Dear User,\n\n");
                body.append("We've detected unusual activity from your health sensors at ").append(record.getTimestamp()).append(".\n\n");
                body.append("Please review the following:\n\n");
                
                for (String alertMessage : alertMessages) {
                    body.append("- ").append(alertMessage).append("\n");
                }
                
                body.append("\nIt's important to pay attention to these changes and consider taking action.\n\n");
                body.append("Your well-being is our priority. This is an automated alert and not medical advice. ");
                body.append("Always consult a qualified healthcare professional for any health concerns.");
                
                message.setText(body.toString());
                Transport.send(message); // Send the email
                
                System.out.println("Email alert sent for device: " + record.getDeviceId());
                
            } catch (MessagingException e) {
                System.err.println("Failed to send email alert: " + e.getMessage());
            }
        }
    }
    
    // PostgreSQL Sink Configuration: Creates a Flink JDBC Sink to write IoTRecord objects to PostgreSQL.
    private static SinkFunction<IoTRecord> createPostgresSink() {
        return JdbcSink.sink(
                "INSERT INTO anomalies (device_id, timestamp, heart_rate, temperature, " +
                "systolic_pressure, diastolic_pressure, oxygen_saturation, respiration_rate, " +
                "blood_glucose, steps_count, calories_burned, stress_level, is_anomaly) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", // 13 parameters
                (JdbcStatementBuilder<IoTRecord>) (preparedStatement, record) -> {
                    // Map IoTRecord fields to PreparedStatement parameters
                    preparedStatement.setString(1, record.getDeviceId());
                    preparedStatement.setTimestamp(2, Timestamp.valueOf(record.getTimestamp())); // Convert String timestamp to SQL Timestamp
                    preparedStatement.setObject(3, record.getHeartRate());
                    preparedStatement.setObject(4, record.getTemperature());
                    preparedStatement.setObject(5, record.getSystolicPressure());
                    preparedStatement.setObject(6, record.getDiastolicPressure());
                    preparedStatement.setObject(7, record.getOxygenSaturation());
                    preparedStatement.setObject(8, record.getRespirationRate());
                    preparedStatement.setObject(9, record.getBloodGlucose());
                    preparedStatement.setObject(10, record.getStepsCount());
                    preparedStatement.setObject(11, record.getCaloriesBurned());
                    preparedStatement.setObject(12, record.getStressLevel());
                    preparedStatement.setBoolean(13, record.isAnomaly());
                },
                org.apache.flink.connector.jdbc.JdbcExecutionOptions.builder()
                        .withBatchSize(100) // Batch 100 records
                        .withBatchIntervalMs(5000) // Or flush every 5 seconds
                        .withMaxRetries(3) // Retry failed batches 3 times
                        .build(),
                new org.apache.flink.connector.jdbc.JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl("jdbc:postgresql://postgres:5432/iot_db") // JDBC URL for PostgreSQL
                        .withDriverName("org.postgresql.Driver") // JDBC Driver
                        .withUsername("user") // DB username
                        .withPassword("password") // DB password
                        .build()
        );
    }
    
    // IoT Record Data Class: Represents the schema of the IoT sensor data.
    // This class is used for deserialization from JSON and serialization to PostgreSQL.
    public static class IoTRecord {
        private String deviceId;
        private String timestamp;
        private Double heartRate;
        private Double temperature;
        private Double systolicPressure;
        private Double diastolicPressure;
        private Double oxygenSaturation;
        private Double respirationRate;
        private Double bloodGlucose;
        private Integer stepsCount;
        private Double caloriesBurned;
        private Double stressLevel;
        private boolean isAnomaly; // Flag to indicate if an anomaly was detected for this record
        
        // Default constructor for Jackson deserialization
        public IoTRecord() {}
        
        // Parameterized constructor
        public IoTRecord(String deviceId, String timestamp, Double heartRate, Double temperature,
                         Double systolicPressure, Double diastolicPressure, Double oxygenSaturation,
                         Double respirationRate, Double bloodGlucose, Integer stepsCount,
                         Double caloriesBurned, Double stressLevel) {
            this.deviceId = deviceId;
            this.timestamp = timestamp;
            this.heartRate = heartRate;
            this.temperature = temperature;
            this.systolicPressure = systolicPressure;
            this.diastolicPressure = diastolicPressure;
            this.oxygenSaturation = oxygenSaturation;
            this.respirationRate = respirationRate;
            this.bloodGlucose = bloodGlucose;
            this.stepsCount = stepsCount;
            this.caloriesBurned = caloriesBurned;
            this.stressLevel = stressLevel;
            this.isAnomaly = false; // Default anomaly status
        }
        
        // Getters and Setters for all fields (required by Flink's POJO type information extraction and Jackson)
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
        
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        
        public Double getHeartRate() { return heartRate; }
        public void setHeartRate(Double heartRate) { this.heartRate = heartRate; }
        
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        
        public Double getSystolicPressure() { return systolicPressure; }
        public void setSystolicPressure(Double systolicPressure) { this.systolicPressure = systolicPressure; }
        
        public Double getDiastolicPressure() { return diastolicPressure; }
        public void setDiastolicPressure(Double diastolicPressure) { this.diastolicPressure = diastolicPressure; }
        
        public Double getOxygenSaturation() { return oxygenSaturation; }
        public void setOxygenSaturation(Double oxygenSaturation) { this.oxygenSaturation = oxygenSaturation; }
        
        public Double getRespirationRate() { return respirationRate; }
        public void setRespirationRate(Double respirationRate) { this.respirationRate = respirationRate; }
        
        public Double getBloodGlucose() { return bloodGlucose; }
        public void setBloodGlucose(Double bloodGlucose) { this.bloodGlucose = bloodGlucose; }
        
        public Integer getStepsCount() { return stepsCount; }
        public void setStepsCount(Integer stepsCount) { this.stepsCount = stepsCount; }
        
        public Double getCaloriesBurned() { return caloriesBurned; }
        public void setCaloriesBurned(Double caloriesBurned) { this.caloriesBurned = caloriesBurned; }
        
        public Double getStressLevel() { return stressLevel; }
        public void setStressLevel(Double stressLevel) { this.stressLevel = stressLevel; }
        
        public boolean isAnomaly() { return isAnomaly; }
        public void setAnomaly(boolean anomaly) { isAnomaly = anomaly; }
        
        @Override
        public String toString() {
            return "IoTRecord{" +
                   "deviceId='" + deviceId + '\'' +
                   ", timestamp='" + timestamp + '\'' +
                   ", heartRate=" + heartRate +
                   ", temperature=" + temperature +
                   ", systolicPressure=" + systolicPressure +
                   ", diastolicPressure=" + diastolicPressure +
                   ", oxygenSaturation=" + oxygenSaturation +
                   ", respirationRate=" + respirationRate +
                   ", bloodGlucose=" + bloodGlucose +
                   ", stepsCount=" + stepsCount +
                   ", caloriesBurned=" + caloriesBurned +
                   ", stressLevel=" + stressLevel +
                   ", isAnomaly=" + isAnomaly +
                   '}';
        }
    }
}
