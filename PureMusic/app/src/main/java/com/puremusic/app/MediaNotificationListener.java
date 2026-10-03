package com.puremusic.app;

import android.service.notification.NotificationListenerService;

public class MediaNotificationListener extends NotificationListenerService {
    // The system binds this service after the user grants notification access.
    // MediaSessionManager then allows PureMusic to see and control active sessions.
}
