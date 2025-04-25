/**
 *  Home Environment Aggregator and Logic
 *
 *  Author: Jeffrey Zimmerman
 *  Version: 1.0
 *  License: MIT
 *
 *  Purpose:
 *  Monitors temperature and humidity sensors, assigns room names, calculates environmental summaries,
 *  detects humidity spikes, manages exhaust fan control, and updates Hub Variables for use in dashboards or automations.
 */

definition(
    name: "Home Environment Aggregator and Logic",
    namespace: "zimm",
    author: "Jeffrey Zimmerman",
    description: "Analyze and respond to your home's environmental conditions using smart rules and sensor data.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
)

// The full logic code goes below this point in your environment.
