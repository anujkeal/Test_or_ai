package com.keal.nexus.device

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.hardware.camera2.CameraManager
import java.net.URLEncoder

/**
 * Small allow-listed Android action router. It never executes arbitrary model-generated intents.
 * External-app operations are limited to explicit, user-spoken commands.
 */
class NexusDeviceActions(private val context: Context) {
    fun execute(spoken: String): String? {
        val command = spoken.trim()
        val lower = command.lowercase()
        if (command.isBlank()) return null

        // Parse combined YouTube requests before the generic "open <app>" shortcut.
        // A YouTube search is not the same as guaranteed automatic playback.
        val youtubePlay = Regex(
            """^(?:(?:please\s+)?(?:open|launch|start)\s+youtube\s+(?:and\s+)?(?:play|search(?:\s+for)?)\s+|(?:play|search\s+for)\s+)(.+?)(?:\s+(?:song|video)\s+on\s+youtube)?$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(command)
        if (youtubePlay != null) {
            val title = youtubePlay.groupValues[1].trim()
                .replace(Regex("""\s+on\s+youtube$""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (title.isBlank()) return "Tell me which song or video to search for."
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(title, "UTF-8")
            return try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened YouTube search results for $title. Choose a result to play."
            } catch (e: Exception) {
                "I couldn't open YouTube: ${e.message ?: "no compatible app available"}."
            }
        }

        if (lower.matches(Regex("^(turn on|switch on|enable) (the )?flashlight$"))) {
            return setTorch(true)
        }
        if (lower.matches(Regex("^(turn off|switch off|disable) (the )?flashlight$"))) {
            return setTorch(false)
        }
        if (lower in listOf("open accessibility settings", "enable app control", "set up accessibility")) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "I opened Accessibility settings. Choose NEXUS app interaction and enable it only if you want app navigation, scrolling and typing."
        }
        if (lower in listOf("go back", "press back", "navigate back")) {
            return if (NexusAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) "Going back." else "Enable NEXUS app interaction in Accessibility settings to use the back action."
        }
        if (lower in listOf("go home", "press home", "open home screen")) {
            return if (NexusAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) "Going to the home screen." else "Enable NEXUS app interaction in Accessibility settings to use the home action."
        }
        if (lower in listOf("open notifications", "show notifications")) {
            return if (NexusAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) "Opening notifications." else "Enable NEXUS app interaction in Accessibility settings first."
        }
        if (lower in listOf("open quick settings", "show quick settings")) {
            return if (NexusAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) "Opening quick settings." else "Enable NEXUS app interaction in Accessibility settings first."
        }

        if (lower == "scroll down" || lower == "scroll up") {
            val direction = if (lower.endsWith("down")) "down" else "up"
            return if (NexusAccessibilityService.scroll(direction)) "Scrolled $direction." else "I couldn't find a scrollable area. Open the app and enable NEXUS app interaction in Accessibility settings."
        }
        if (lower.startsWith("type ")) {
            val text = command.substringAfter("type ").trim()
            if (text.isBlank()) return "Tell me what to type."
            return if (NexusAccessibilityService.typeIntoFocusedField(text)) "Typed your text into the focused field." else "I couldn't find an editable text field. Tap the field first, then say: type followed by your text."
        }
        if (lower.startsWith("confirm tap ")) {
            val label = command.substringAfter("confirm tap ").trim()
            if (label.isBlank()) return "Tell me which visible control to tap."
            return if (NexusAccessibilityService.tapVisibleText(label)) "Tapped the visible control labelled $label." else "I couldn't find a tappable control labelled $label. Make sure the app is open and NEXUS app interaction is enabled."
        }

        val alarmMatch = Regex("(?:set|create) (?:an? )?alarm for (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(command)
        if (alarmMatch != null) {
            var hour = alarmMatch.groupValues[1].toIntOrNull() ?: return "I couldn't understand that alarm time."
            val minute = alarmMatch.groupValues[2].toIntOrNull() ?: 0
            val meridiem = alarmMatch.groupValues[3].lowercase()
            if (minute !in 0..59 || hour !in 0..23) return "That alarm time is invalid."
            if (meridiem == "pm" && hour < 12) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) return "No alarm app is available."
            context.startActivity(intent)
            return "I opened the alarm screen for %02d:%02d. Please confirm it there.".format(hour, minute)
        }

        if (lower.startsWith("set a timer for ") || lower.startsWith("start a timer for ")) {
            val match = Regex("(\\d+)\\s*(seconds?|minutes?|hours?)", RegexOption.IGNORE_CASE).find(lower)
                ?: return "Tell me a duration, for example: set a timer for 5 minutes."
            val amount = match.groupValues[1].toIntOrNull() ?: return "That timer duration is invalid."
            val unit = match.groupValues[2]
            val seconds = when {
                unit.startsWith("hour") -> amount * 3600
                unit.startsWith("minute") -> amount * 60
                else -> amount
            }
            if (seconds <= 0) return "Timer duration must be greater than zero."
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) return "No timer app is available."
            context.startActivity(intent)
            return "I opened the timer for $amount $unit. Please start it in the Clock app."
        }

        if ((lower.startsWith("play ") && lower !in listOf("play local music", "play music from my phone")) || lower.startsWith("search youtube for ") || lower.startsWith("find on youtube ")) {
            val query = when {
                lower.startsWith("search youtube for ") -> command.substringAfter("search youtube for ", "")
                lower.startsWith("find on youtube ") -> command.substringAfter("find on youtube ", "")
                else -> command.substringAfter("play ", "").removeSuffix(" on youtube").removeSuffix(" from youtube")
            }.trim()
            if (query.isBlank()) return "Tell me the song or video name."
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "I searched YouTube for $query. Choose a result to play."
        }

        val appName = when {
            lower.startsWith("open ") -> command.substringAfter("open ").trim().lowercase()
            lower.startsWith("launch ") -> command.substringAfter("launch ").trim().lowercase()
            else -> null
        }
        if (appName != null) {
            val packages = mapOf(
                "youtube" to listOf("com.google.android.youtube"),
                "youtube music" to listOf("com.google.android.apps.youtube.music"),
                "chrome" to listOf("com.android.chrome", "com.chrome.beta"),
                "whatsapp" to listOf("com.whatsapp"),
                "instagram" to listOf("com.instagram.android"),
                "spotify" to listOf("com.spotify.music"),
                "maps" to listOf("com.google.android.apps.maps"),
                "google maps" to listOf("com.google.android.apps.maps"),
                "camera" to listOf("com.android.camera2", "com.oplus.camera"),
                "gallery" to listOf("com.google.android.apps.photos", "com.oplus.gallery"),
                "settings" to listOf("com.android.settings"),
                "calculator" to listOf("com.google.android.calculator", "com.android.calculator2")
            )
            val packageNames = packages[appName]
            if (packageNames != null) {
                for (packageName in packageNames) {
                    val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        return "Opening $appName."
                    }
                }
                return "$appName does not appear to be installed."
            }
            return "I don't have an app shortcut for $appName yet. Try saying the exact app name, or add it to NEXUS app shortcuts."
        }

        if (lower in listOf("play local music", "open local music", "play music from my phone", "choose local song")) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) {
                return "No music player is registered on this phone. Install or choose a music player, then try again."
            }
            context.startActivity(intent)
            return "Opening your music player. Choose a downloaded song there."
        }
        if (lower in listOf("pause music", "resume music", "next song", "previous song", "play music")) {
            return "I heard the media command. Direct control of another app's playback depends on whether that music app exposes an active media session. Open the player and use its playback controls for now."
        }
        return null
    }

    private fun setTorch(enabled: Boolean): String {
        return try {
            val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return "This phone does not expose a flashlight to Android."
            camera.setTorchMode(id, enabled)
            if (enabled) "Flashlight turned on." else "Flashlight turned off."
        } catch (error: Exception) {
            "I couldn't change the flashlight: ${error.message ?: "Android denied the request"}."
        }
    }
}