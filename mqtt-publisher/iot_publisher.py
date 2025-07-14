"""
IoT Sensor Data Publisher (MQTT Client)

This script simulates a virtual IoT health monitoring device that generates
time-series sensor data (heart rate, temperature, blood pressure, oxygen saturation,
respiration rate, blood glucose, stress level, steps count, calories burned)
and publishes it to an MQTT broker.

It includes advanced simulation logic to:
- Generate realistic physiological data with inherent fluctuations and Gaussian noise.
- Simulate daily activity cycles (sleep, sedentary, moderate, active) that dynamically
  influence baseline sensor readings.
- Randomly schedule and inject specific types of anomalies (e.g., fever spikes,
  tachycardia, hypoxia, panic attacks) at predefined frequencies to test the
  downstream anomaly detection capabilities.
- Ensure physiological values stay within reasonable human bounds, even during anomalies.
- Implement daily resets for anomaly scheduling to ensure continuous simulation.

This publisher is essential for providing a continuous stream of diverse data,
including both normal and anomalous events, to the real-time streaming pipeline
for robust testing and demonstration.

Configuration:
- MQTT_BROKER: Hostname or IP of the MQTT broker (e.g., 'localhost' for Docker Compose setup).
- MQTT_PORT: Port of the MQTT broker (e.g., 1883).
- TOPIC: MQTT topic to publish data to (e.g., 'iot/sensor_data').
- PUBLISH_INTERVAL_SECONDS: Frequency at which new data records are published.
- ANOMALY_DURATION_SECONDS: Duration for which a single anomalous record is generated per event.
- NUM_ANOMALIES_PER_DAY: Number of distinct anomaly events scheduled per 24-hour cycle.
- ANOMALY_TYPES: List of predefined anomaly types that can be simulated.

Author: Sai Gowtham Reddy Udumula
"""

import paho.mqtt.client as mqtt
import time
import json
import random
from datetime import datetime, timedelta
from typing import Dict, Any, List, Tuple, Set

# --- Configuration ---
MQTT_BROKER = "localhost"
MQTT_PORT = 1883
TOPIC = "iot/sensor_data"
DEVICE_COUNT = 1 # Only one virtual device simulated by this script instance
PUBLISH_INTERVAL_SECONDS = 1 # Data published every second

# --- Anomaly Scheduling Control for the Single Device ---
ANOMALY_DURATION_SECONDS = 1 # 1 second, meaning 1 anomalous record per event.
NUM_ANOMALIES_PER_DAY = 3 # Exactly 3 anomaly events (now 3 records) per 24-hour cycle

# Types of anomalies to pick from (chosen randomly for each scheduled event)
ANOMALY_TYPES = [
    "fever_spike",        # Sudden high temperature
    "tachycardia_spike",  # Sudden high heart rate
    "bradycardia_spike",  # Sudden low heart rate
    "hypoxia_spike",      # Sudden low oxygen saturation
    "hypertension_spike", # Sudden high blood pressure
    "hypoglycemia_spike", # Sudden low blood glucose
    "hyperglycemia_spike",# Sudden high blood glucose
    "respiratory_distress_spike", # Sudden high respiration rate
    "panic_attack_spike"  # Sudden high stress level
]

# --- Internal State for the single device ---
# Stores current baselines, activity levels, and scheduled/active anomalies.
device_state: Dict[str, Dict[str, Any]] = {} 

# --- MQTT Callbacks ---
def on_connect(client: mqtt.Client, userdata: Any, flags: Dict[str, Any], rc: int):
    """Callback for when the MQTT client connects to the broker."""
    if rc == 0:
        print("Connected to MQTT Broker!")
    else:
        print(f"Failed to connect, return code {rc}\n")

def on_publish(client: mqtt.Client, userdata: Any, mid: int):
    """Callback for when a message is published (can be extended for logging)."""
    pass

# --- Schedule Anomaly Times for the Day ---
def generate_daily_anomaly_schedule() -> List[int]:
    """Generates random anomaly start times (in seconds from midnight) for a 24-hour period."""
    scheduled_seconds = []
    min_event_spacing_seconds = 20 * 60 # 20 minutes minimum between random anomaly starts

    for _ in range(NUM_ANOMALIES_PER_DAY):
        while True:
            potential_start_sec = random.randint(0, 24 * 3600 - ANOMALY_DURATION_SECONDS)
            is_too_close = False
            for existing_sec in scheduled_seconds:
                if abs(potential_start_sec - existing_sec) < min_event_spacing_seconds:
                    is_too_close = True
                    break
            if not is_too_close:
                scheduled_seconds.append(potential_start_sec)
                break
    
    scheduled_seconds.sort() # Keep them in order for easier processing
    return scheduled_seconds

# --- Initialization Function for Device Baselines and States ---
def initialize_device_state(device_id: str):
    """
    Initializes the baseline health metrics and activity state for a given device.
    Also generates the daily anomaly schedule for the device.
    """
    base_hr = random.randint(60, 80)
    base_temp = round(random.uniform(36.7, 37.2), 2)
    base_sys_bp = random.randint(110, 125)
    base_dia_bp = random.randint(70, 80)
    base_spo2 = random.randint(97, 99)
    base_rr = random.randint(14, 18)
    base_glucose = random.randint(80, 95)
    base_stress = random.randint(0, 2)

    current_activity_level = 1 # 0: sleep, 1: sedentary, 2: moderate, 3: active
    
    # Generate today's schedule for this device
    daily_anomaly_seconds_schedule = generate_daily_anomaly_schedule()
    today = datetime.now().date()
    scheduled_anomaly_start_times_dt = [
        datetime.combine(today, (datetime.min + timedelta(seconds=s)).time())
        for s in daily_anomaly_seconds_schedule
    ]
    # If any scheduled times are in the past when starting, reschedule them for tomorrow
    current_time = datetime.now()
    for i, scheduled_dt in enumerate(scheduled_anomaly_start_times_dt):
        if scheduled_dt < current_time:
            scheduled_anomaly_start_times_dt[i] += timedelta(days=1)
    
    scheduled_anomaly_start_times_dt.sort()

    device_state[device_id] = { 
        "baselines": {
            "heart_rate": base_hr,
            "temperature": base_temp,
            "systolic_pressure": base_sys_bp,
            "diastolic_pressure": base_dia_bp,
            "oxygen_saturation": base_spo2,
            "respiration_rate": base_rr,
            "blood_glucose": base_glucose,
            "stress_level": base_stress,
        },
        "current_activity_level": current_activity_level,
        "last_activity_change_time": datetime.now(),
        "active_anomaly": None, # Stores the type of anomaly currently active
        "scheduled_anomaly_start_times_dt": scheduled_anomaly_start_times_dt,
        "triggered_events_today": set(), # Tracks which scheduled events have been triggered today
        "last_reset_date": today, # Keep track of last date reset occurred
        "current_hour_of_day": datetime.now().hour # To track hourly activity changes
    }
    print(f"DEVICE {device_id}: Today's anomaly schedule: {[dt.strftime('%H:%M:%S') for dt in scheduled_anomaly_start_times_dt]}")


# --- Data Generation Function ---
def generate_sensor_data(device_id: str) -> Dict[str, Any]:
    """
    Generates a single set of sensor data for a given device,
    applying normal fluctuations, activity-based factors, and anomaly injection.
    """
    state = device_state[device_id] 
    baselines = state["baselines"]
    current_time = datetime.now()

    # --- Simulate Daily Cycles for Activity/Sleep/Stress ---
    current_hour = current_time.hour
    if current_hour != state["current_hour_of_day"]:
        state["current_hour_of_day"] = current_hour
        # Adjust activity level based on hour of day
        if 0 <= current_hour < 6: state["current_activity_level"] = 0 # Sleep
        elif 6 <= current_hour < 8: state["current_activity_level"] = random.choice([1,2]) # Wake up / light activity
        elif 8 <= current_hour < 18: state["current_activity_level"] = random.choice([2,3]) # Work / active
        elif 18 <= current_hour < 22: state["current_activity_level"] = random.choice([1,2]) # Evening / winding down
        else: state["current_activity_level"] = random.choice([0,1]) # Late night / sleep

    # Factors based on current activity level
    activity_factor_hr = 1 + (state["current_activity_level"] * 0.05)
    activity_factor_temp = 1 + (state["current_activity_level"] * 0.005)
    activity_factor_bp = 1 + (state["current_activity_level"] * 0.03)
    activity_factor_rr = 1 + (state["current_activity_level"] * 0.08)
    activity_factor_stress = state["current_activity_level"] * 0.5 

    # Calculate BASE values (before noise and anomaly injection)
    hr_base = baselines["heart_rate"] * activity_factor_hr
    temp_base = baselines["temperature"] * activity_factor_temp
    sys_bp_base = baselines["systolic_pressure"] * activity_factor_bp
    dia_bp_base = baselines["diastolic_pressure"] * activity_factor_bp
    rr_base = baselines["respiration_rate"] * activity_factor_rr
    stress_base = baselines["stress_level"] + activity_factor_stress

    # Add small normal fluctuations (Gaussian noise)
    hr = random.gauss(hr_base, 1.5) 
    temp = random.gauss(temp_base, 0.05) 
    sys_bp = random.gauss(sys_bp_base, 2) 
    dia_bp = random.gauss(dia_bp_base, 1.5) 
    spo2 = random.gauss(baselines["oxygen_saturation"], 0.2) 
    rr = random.gauss(rr_base, 0.8) 
    glucose = random.gauss(baselines["blood_glucose"], 3) 
    stress = random.gauss(stress_base, 0.3) 

    # Ensure values stay within reasonable human physiological bounds (tighter for normal data)
    hr = max(55, min(105, hr))
    temp = max(36.0, min(38.0, temp))
    sys_bp = max(85, min(135, sys_bp))
    dia_bp = max(55, min(85, dia_bp))
    spo2 = max(94, min(100, spo2))
    rr = max(10, min(22, rr))
    glucose = max(65, min(120, glucose))
    stress = max(0, min(5, stress))

    steps_count = random.randint(state["current_activity_level"] * 5, state["current_activity_level"] * 20)
    calories_burned = random.uniform(0.8 + state["current_activity_level"] * 0.2, 2.0 + state["current_activity_level"] * 0.5)

    # --- Anomaly Injection Logic (Applies only for the 1-second duration) ---
    current_time_no_ms = current_time.replace(microsecond=0) 
    anomaly_to_trigger = None
    
    # Check if current time is within the trigger window for any scheduled anomaly
    for i, scheduled_dt in enumerate(state["scheduled_anomaly_start_times_dt"]):
        # Normalize scheduled_dt to current day if it's from a previous day's schedule
        if scheduled_dt.date() < current_time.date():
            scheduled_dt = scheduled_dt.replace(year=current_time.year, month=current_time.month, day=current_time.day)
            # If it's still in the past on current day, roll it to tomorrow
            if scheduled_dt < current_time.replace(hour=0, minute=0, second=0, microsecond=0):
                scheduled_dt += timedelta(days=1)
            state["scheduled_anomaly_start_times_dt"][i] = scheduled_dt 
        
        trigger_window_start = scheduled_dt
        trigger_window_end = scheduled_dt + timedelta(seconds=ANOMALY_DURATION_SECONDS * 2) 

        if trigger_window_start <= current_time_no_ms < trigger_window_end and \
           (scheduled_dt.hour, scheduled_dt.minute, scheduled_dt.second) not in state["triggered_events_today"]:
            
            anomaly_to_trigger = random.choice(ANOMALY_TYPES)
            state["triggered_events_today"].add((scheduled_dt.hour, scheduled_dt.minute, scheduled_dt.second))
            
            print(f"DEVICE {device_id}: Triggering scheduled anomaly: {anomaly_to_trigger} at {current_time.strftime('%H:%M:%S')}")
            break # Found and handled a trigger, exit loop

    # Apply anomaly effect if one was chosen to trigger for *this specific second*
    if anomaly_to_trigger:
        if anomaly_to_trigger == "fever_spike":
            temp = random.uniform(39.5, 41.0) 
            hr = random.gauss(hr * 1.25, 5) 
        elif anomaly_to_trigger == "tachycardia_spike":
            hr = random.randint(130, 160) 
        elif anomaly_to_trigger == "bradycardia_spike":
            hr = random.randint(35, 45) 
        elif anomaly_to_trigger == "hypoxia_spike":
            spo2 = random.randint(80, 89) 
            rr = random.gauss(rr * 1.5, 3) 
        elif anomaly_to_trigger == "hypertension_spike":
            sys_bp = random.randint(160, 190) 
            dia_bp = random.randint(95, 110) 
        elif anomaly_to_trigger == "hypoglycemia_spike":
            glucose = random.randint(30, 60) 
            hr = random.gauss(hr * 1.1, 3) 
        elif anomaly_to_trigger == "hyperglycemia_spike":
            glucose = random.randint(200, 350)
        elif anomaly_to_trigger == "respiratory_distress_spike":
            rr = random.randint(25, 35)
        elif anomaly_to_trigger == "panic_attack_spike":
            stress = random.randint(8, 10)
            hr = random.gauss(hr * 1.15, 5) 
            sys_bp = random.gauss(sys_bp * 1.1, 5) 

    # Daily reset: At the very beginning of a new day, reset the triggered events set and regenerate schedule
    if current_time.date() > state.get("last_reset_date", datetime.min.date()):
        state["triggered_events_today"] = set()
        state["last_reset_date"] = current_time.date()
        state["scheduled_anomaly_start_times_dt"] = [
            datetime.combine(current_time.date(), (datetime.min + timedelta(seconds=s)).time())
            for s in generate_daily_anomaly_schedule()
        ]
        state["scheduled_anomaly_start_times_dt"].sort() # Ensure sorted
        print(f"DEVICE {device_id}: New day cycle started. New anomaly schedule: {[dt.strftime('%H:%M:%S') for dt in state['scheduled_anomaly_start_times_dt']]}")

    return {
        "device_id": f"device_{device_id}",
        "timestamp": current_time.isoformat(),
        "heart_rate": int(round(hr)), 
        "temperature": round(temp, 2),
        "systolic_pressure": int(round(sys_bp)),
        "diastolic_pressure": int(round(dia_bp)),
        "oxygen_saturation": int(round(spo2)),
        "respiration_rate": int(round(rr)),
        "blood_glucose": int(round(glucose)),
        "steps_count": int(steps_count), 
        "calories_burned": round(calories_burned, 2),
        "stress_level": int(stress)
    }

# --- Main Publisher Logic ---
print(f"Starting MQTT publisher for {DEVICE_COUNT} virtual device. Press Ctrl+C to stop.")
client = mqtt.Client() # DeprecationWarning here is fine
client.on_connect = on_connect
client.on_publish = on_publish

# Initialize state for the single device (always "device_0")
initialize_device_state(f"device_0") 

try:
    client.connect(MQTT_BROKER, MQTT_PORT, 60)
    client.loop_start()

    last_publish_time = time.time()

    while True:
        current_loop_time = time.time()
        device_id_str = f"device_0"
        if current_loop_time - last_publish_time >= PUBLISH_INTERVAL_SECONDS:
            sensor_data = generate_sensor_data(device_id_str)
            payload = json.dumps(sensor_data)
            client.publish(TOPIC, payload)
            print(f"Device 0 published: {payload}")
            last_publish_time = current_loop_time

        time.sleep(0.01) # Small sleep to prevent 100% CPU usage
except KeyboardInterrupt:
    print("\nStopping publisher due to user interrupt (Ctrl+C).")
except Exception as e:
    print(f"An error occurred: {e}")
finally:
    client.loop_stop()    # Stop the loop
    client.disconnect()   # Disconnect from the broker
    print("MQTT client disconnected. Exiting.")
