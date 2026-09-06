package com.example.connect_sphere.mock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;


/* Composite key mock and mock string, with foreign key mock, need to map one -to one one to many */

@Entity
@Table(name = "mock_references")
@IdClass(MockReferenceId.class)
@Getter
@Setter
public class MockReference{
    @Id
    @ManyToOne
    @JoinColumn(name="mock_id")
    private Mock mock;

    @Id
    @Column(name="mock_string2",nullable=false,length=50)
    private String mockString;
    public MockReference() {
    }





}
