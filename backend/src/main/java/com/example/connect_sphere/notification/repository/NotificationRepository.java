package com.example.connect_sphere.notification.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.notification.entity.Notification;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** EO09/EO19: "can view the notification" / "retains the notification in
     * the organiser's notification list" — newest first, so the most recent
     * decision is what a returning organiser sees at the top. */
    List<Notification> findByRecipientUserIdOrderByOccurredAtDesc(UUID recipientUserId);

    /** Powers an unread-count badge without pulling every row down to count
     * them client-side. */
    long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);
}
