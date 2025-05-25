# Home Environment Aggregator and Logic

A Hubitat app to aggregate temperature, humidity, and other sensor data across rooms, calculate floor and whole-home averages, and provide environmental logic. It also monitors specific areas for humidity spikes and controls an exhaust fan to normalize humidity within tolerance.

## Features
- Aggregates temperature, humidity, and lux data across multiple rooms.
- Calculates averages for rooms, floors, and the whole home.
- Detects humidity spikes in specific areas (e.g., bathroom) and controls an exhaust fan.
- Ensures the fan stays on until the spike is normalized, ignoring motion events after activation.
- Supports notifications for temperature deltas between floors.
- Visualizes humidity data via Home Assistant (requires integration).

## Installation via Hubitat Package Manager (HPM)
1. Install the Hubitat Package Manager (HPM) if you haven’t already:
   - Follow the instructions at [Hubitat Package Manager Documentation](https://hubitatpackagemanager.hubitatcommunity.com).
2. In HPM, select “Install” > “From a URL.”
3. Enter the URL to the package manifest: `https://raw.githubusercontent.com/JeffreyZimms/Home-Environment-Aggregator/main/packageManifest.json`
4. Follow the prompts to install the app.
5. Configure the app in Hubitat under “Apps” > “Home Environment Aggregator and Logic.”

## Manual Installation
1. Copy the code from `Home_Environment_Aggregator_and_Logic.groovy`.
2. In Hubitat, go to “Apps Code” > “New App” > “Import.”
3. Paste the code and save.
4. Add the app under “Apps” > “Add User App” > “Home Environment Aggregator and Logic.”

## Usage
- Configure areas, sensors, and virtual devices in the app settings.
- Set up humidity spike detection and exhaust fan control for specific areas.
- Monitor averages and spikes via Hubitat or integrate with Home Assistant for visualization.

## Updates
- This app supports updates via HPM. Check for updates in HPM under “Update” to get the latest version and release notes.

## Release Notes
### v1.0.0 (2025-05-25)
- Initial release.
- Aggregates temperature, humidity, and lux data.
- Calculates room, floor, and whole-home averages.
- Detects humidity spikes and controls exhaust fan.
- Ensures fan stays on until spike is normalized, ignoring motion events.
- Supports Home Assistant integration for visualization.

## Support
- For issues or feature requests, create an issue in this repository.
- Join the Hubitat community discussion: [Link to community thread, if available].

## Donation
If you find this app useful, consider supporting development: [Link to PayPal or donation page, if desired].
