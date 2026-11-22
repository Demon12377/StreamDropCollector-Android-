package com.example.core.models

import kotlinx.serialization.Serializable

@Serializable
enum class Platform(val displayName: String) {
    TWITCH("Twitch"),
    KICK("Kick")
}

@Serializable
enum class ConnectionStatus(val label: String) {
    CHECKING("Checking..."),
    ONLINE("Online"),
    OFFLINE("Offline"),
    ERROR("Error"),
    DISABLED("Disabled")
}

@Serializable
enum class MiningPriorityMode(val label: String) {
    AVAILABILITY_THEN_PROGRESS("Availability + Progress"),
    ENDING_SOONEST("Ending Soonest"),
    LEAST_TIME_TO_NEXT_REWARD("Least Time To Next Reward"),
    HIGHEST_COMPLETION("Highest Completion")
}

@Serializable
enum class InventorySortMode(val label: String) {
    DEFAULT("Default"),
    ENDING_SOONEST("Ending soonest"),
    GAME_NAME("Game (A-Z)"),
    LEAST_TIME_REMAINING("Least time left")
}

@Serializable
enum class UpdateFrequency(val label: String) {
    ON_LAUNCH("Every time the app starts"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    NEVER("Never")
}

@Serializable
enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System")
}

@Serializable
enum class AudioLoopMode {
    OFF,
    HEAD_ONLY
}

enum class NotificationType {
    DROP_CLAIMED,
    DROP_READY_TO_CLAIM,
    DROP_CLAIM_FAILED,
    ACCOUNT_NOT_LINKED,
    UPDATE_AVAILABLE,
    APP_UPDATED
}
