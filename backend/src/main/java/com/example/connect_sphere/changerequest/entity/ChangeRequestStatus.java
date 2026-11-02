package com.example.connect_sphere.changerequest.entity;

/** EO03/EC-NEW3: a change request is decided once; nothing reopens it. */
public enum ChangeRequestStatus {
    pending,
    approved,
    rejected,
    withdrawn
}
