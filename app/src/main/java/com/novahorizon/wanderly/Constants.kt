package com.novahorizon.wanderly

object Constants {
    const val PREFS_NAME = "WanderlyPrefs"
    const val KEY_LAST_VISIT = "last_visit_date"
    const val KEY_USERNAME = "username"
    const val KEY_REMEMBER_ME = "remember_me"
    const val KEY_ONBOARDING_SEEN = "onboarding_seen"
    const val KEY_MISSION_TEXT = "mission_text"
    const val KEY_MISSION_TARGET = "mission_target_name"
    const val KEY_MISSION_CITY = "mission_target_city"
    const val KEY_MISSION_HISTORY = "mission_history"
    const val KEY_MISSION_TARGET_LAT = "mission_target_lat"
    const val KEY_MISSION_TARGET_LNG = "mission_target_lng"
    const val KEY_MISSION_TARGET_LAT_TYPED = "mission_target_lat_typed"
    const val KEY_MISSION_TARGET_LNG_TYPED = "mission_target_lng_typed"
    const val KEY_LOCAL_LAST_MISSION_DATE = "local_last_mission_date"
    const val KEY_LOCAL_STREAK_COUNT = "local_streak_count"
    const val KEY_WIDGET_STREAK_COUNT = "widget_streak_count"
    const val KEY_WIDGET_LAST_MISSION_DATE = "widget_last_mission_date"
    const val KEY_WIDGET_STREAK_SAVED_AT_MILLIS = "widget_streak_saved_at_millis"
    const val KEY_WIDGET_LAST_SYNC_SUCCEEDED = "widget_last_sync_succeeded"

    const val AUTH_CALLBACK_SCHEME = "wanderly"
    const val AUTH_CALLBACK_HOST = "auth"
    const val AUTH_CALLBACK_LEGACY_HOST = "login"
    const val AUTH_CALLBACK_PATH = "/callback"
    const val INVITE_CALLBACK_SCHEME = "wanderly"
    const val INVITE_CALLBACK_HOST = "invite"
    const val INVITE_WEB_SCHEME = "https"
    const val INVITE_WEB_HOST = "wanderly.app"
    const val INVITE_PATH_SEGMENT = "invite"
    const val INVITE_QUERY_PARAMETER = "invite"
    const val KEY_PENDING_INVITE_CODE = "pending_invite_code"

    const val TABLE_PROFILES = "profiles"
    const val STORAGE_BUCKET_AVATARS = "avatars"
    
    const val DAILY_HONEY_REWARD = 10
    const val MISSION_HONEY_REWARD = 50
}
