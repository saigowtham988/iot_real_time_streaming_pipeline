-- SQL Script: Initialize PostgreSQL Schema for IoT Anomaly Detection Pipeline
--
-- This script creates the 'anomalies' table, which serves as the persistent storage
-- for processed IoT sensor data and detected anomalies from the real-time streaming pipeline.
-- The schema aligns with the data structure produced by the IoT publisher (Python)
-- and consumed/transformed by the Flink anomaly detection job (Java).
--
-- Table: anomalies
-- Stores individual sensor readings along with processing metadata and anomaly flags.
--
-- Author: Sai Gowtham Reddy Udumula
--

CREATE TABLE IF NOT EXISTS anomalies (
    id SERIAL PRIMARY KEY,                               -- Unique identifier for each record
    device_id VARCHAR(255) NOT NULL,                     -- Identifier for the IoT device
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,         -- Timestamp of the sensor reading (ISO 8601 from Python, converted to SQL Timestamp)
    heart_rate DOUBLE PRECISION,                         -- Heart rate in beats per minute (bpm)
    temperature DOUBLE PRECISION,                        -- Body temperature in Celsius (°C)
    systolic_pressure DOUBLE PRECISION,                  -- Systolic blood pressure in mmHg
    diastolic_pressure DOUBLE PRECISION,                 -- Diastolic blood pressure in mmHg
    oxygen_saturation DOUBLE PRECISION,                  -- Oxygen saturation percentage (%)
    respiration_rate DOUBLE PRECISION,                   -- Respiration rate in breaths per minute
    blood_glucose DOUBLE PRECISION,                      -- Blood glucose level in mg/dL
    steps_count INTEGER,                                 -- Number of steps taken
    calories_burned DOUBLE PRECISION,                    -- Calories burned
    stress_level DOUBLE PRECISION,                       -- Stress level (e.g., on a scale)
    is_anomaly BOOLEAN NOT NULL DEFAULT FALSE,           -- Flag indicating if an anomaly was detected for this record
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP -- Timestamp when the record was inserted into the database
);

-- Optional: Add indexes for optimizing query performance.
-- These indexes are beneficial for common queries, especially for time-series analysis
-- and filtering by device or anomaly status in tools like Grafana.
CREATE INDEX IF NOT EXISTS idx_anomalies_device_id ON anomalies (device_id);
CREATE INDEX IF NOT EXISTS idx_anomalies_timestamp ON anomalies (timestamp DESC); -- Descending for recent data queries
CREATE INDEX IF NOT EXISTS idx_anomalies_is_anomaly ON anomalies (is_anomaly);
CREATE INDEX IF NOT EXISTS idx_anomalies_device_time ON anomalies (device_id, timestamp DESC);
