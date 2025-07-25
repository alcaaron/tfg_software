# BLE Disaster Messenger

> [!NOTE]
> This project is a demo for the Reyax RYLR999 BLE+LoRa modules.

---

A Bluetooth Low Energy (BLE) messenger application for Android designed for disaster communication scenarios. This app connects to BLE-LoRa modules to enable long-range messaging when traditional communication infrastructure is unavailable.

**📺 Watch the full project tutorial on YouTube: [BLE-LoRa Messenger Tutorial](https://youtu.be/9_yHu9rJt58)**

## Features

### 🚨 Disaster Communication
- **Long-range messaging**: Uses BLE-LoRa modules for extended communication range
- **Infrastructure-independent**: Works without cellular towers or internet connectivity
- **Target addressing**: Send messages to specific addresses (1 or 2)
- **Real-time messaging**: Two-way communication with instant message delivery

### 📱 User Interface
- **Clean conversation view**: Chat-like interface showing only actual messages
- **Message history**: Scrollable conversation log with timestamps
- **Target selection**: Dropdown to choose message destination
- **Clear functionality**: Button to clear conversation history
- **Auto-scroll**: Automatically scrolls to newest messages

### 🔧 Technical Features
- **AT command protocol**: Sends formatted `AT+SEND` commands to BLE modules
- **Message parsing**: Extracts actual message content from `+RCV` responses
- **Confirmation filtering**: Automatically filters out `+OK` confirmation messages
- **Proper termination**: Commands terminated with `\r\n` for module compatibility
- **Error handling**: Robust parsing with fallback for unexpected formats

## BLE Module Communication

### Outgoing Messages
```
Format: AT+SEND=<address>,<length>,<message>\r\n
Example: AT+SEND=2,5,HELLO\r\n
```

### Incoming Messages
```
Format: +RCV=<sender>,<length>,<message>,<rssi>,<snr>
Example: +RCV=1,5,HELLO,0,11
Displayed: HELLO
```

### Module Configuration
- **Write Characteristic**: `f000c0c1-0451-4000-b000-000000000000`
- **Notification Characteristic**: `f000c0c2-0451-4000-b000-000000000000`
- **Service UUID**: `4880c12c-fdcb-4077-8920-a450d7f9b907`

## Use Cases

- **Emergency Response**: Communication during natural disasters
- **Remote Operations**: Messaging in areas without cellular coverage
- **Outdoor Activities**: Hiking, camping, or expedition communication
- **Industrial Applications**: Communication in remote work sites
- **Research Projects**: Environmental monitoring or scientific expeditions

## Getting Started

## Getting Started

### Prerequisites
- Android device with BLE support
- BLE-LoRa module (compatible with AT command protocol)
- Android Studio for development/building

### Installation

1. Clone the project to your directory of choice:
```bash
git clone https://github.com/bkolicoski/rylr999-disaster-messenger.git
```

2. Launch Android Studio and select "Open an existing Android Studio project"
3. Navigate to the directory where you cloned the project and double-click on it
4. Wait for Gradle sync to complete
5. Build and install the app on your Android device

### Usage

1. **Connect to BLE Module**: 
   - Launch the app and scan for nearby BLE devices
   - Select your BLE-LoRa module from the list
   
2. **Start Messaging**:
   - Select target address (1 or 2) from the dropdown
   - Type your message in the text field
   - Tap "Send" to transmit the message
   
3. **Receive Messages**:
   - Incoming messages appear automatically in the conversation
   - Only the actual message content is displayed (technical data filtered out)
   
4. **Clear History**:
   - Use the "Clear Messages" button to reset the conversation

## Hardware Requirements

### Supported BLE Modules
- REYAX RYLR998 (tested)
- REYAX RYLR896/890 series
- Other AT command compatible BLE-LoRa modules

### BLE Characteristics
The app expects the following BLE service structure:
- **Service UUID**: `4880c12c-fdcb-4077-8920-a450d7f9b907`
- **Write Characteristic**: `f000c0c1-0451-4000-b000-000000000000` (for sending commands)
- **Notification Characteristic**: `f000c0c2-0451-4000-b000-000000000000` (for receiving data)

## Requirements

This project targets Android 14 and has a min SDK requirement of 21 (Android 5.0). The app requires:

- **Android 5.0+** (API level 21 or higher)
- **Bluetooth Low Energy** support
- **Location permissions** (required for BLE scanning)
- **Nearby devices permissions** (Android 12+)

## Project Structure

The app is built on the foundation of Punch Through's BLE Starter App with the following key modifications:

### Core Components
- **BleOperationsActivity**: Main messaging interface with conversation view
- **ConnectionManager**: Handles BLE connection and communication
- **Message Processing**: 
  - Outgoing: Formats messages as AT commands
  - Incoming: Parses received data and extracts message content
  - Filtering: Removes technical confirmations and status messages

### Key Features Implementation
- **Target Address Selection**: Spinner component for choosing message destinations
- **Message Formatting**: Automatic conversion to `AT+SEND` command format
- **Response Parsing**: Extracts actual message content from `+RCV` responses
- **Clean UI**: Chat-like interface with timestamp display
- **Error Handling**: Robust parsing with graceful fallback for edge cases

## Contributing

### Reporting Issues
Please [open an issue](https://github.com/bkolicoski/rylr999-disaster-messenger/issues/new) to report bugs or request features.

### Development
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test with actual BLE-LoRa hardware
5. Submit a pull request

## Troubleshooting

### Common Issues
- **Can't find BLE device**: Ensure device is in pairing mode and location permissions are granted
- **Messages not sending**: Check BLE connection and verify AT command format
- **No incoming messages**: Verify notification characteristic UUID and ensure notifications are enabled
- **Garbled text**: Check character encoding and ensure proper `\r\n` termination

### Debug Information
Enable Android Studio logcat filtering for `BleOperations` to see detailed communication logs.

## Based On

This project is based on [Punch Through's BLE Starter App](https://github.com/PunchThrough/ble-starter-android), modified for disaster communication use cases with BLE-LoRa modules.

## Licensing

This project is licensed under the Apache 2.0 License. For more details, please see [LICENSE](LICENSE).
