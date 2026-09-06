package com.example.connect_sphere.mock.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/* model for entity mock, ensure all fields are accurate as it will be mapped to the database
 through jpa, note that tables have to exist beforehand as auto-ddl set to validate in the application.properties so as to have one source of truth being the migration files. 



*/
@Entity
@Table(name="mock")
@Getter
@Setter /* auto generates getter and setters for mock using lombok dependency */
public class Mock{
    /* Primary key for Mock entity */
    @Id
    @GeneratedValue(strategy= GenerationType.UUID)
    private UUID id;
    /* Fields mapping columns to the data type in entity, with the field constraints*/
    @Column(name="mock_string",nullable=false,unique=false)
    private String mockString;

    @Column(name="mock_character",nullable=false,unique=false)
    private Character mockCharacter;

    @Column(name="mock_integer",nullable=false,unique=false)
    private int mockInteger;

    @Column(name="mock_boolean",nullable=false,unique=false)
    private boolean mockBoolean;

    @Column(name="created_at",nullable=true)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "mock")
    List<MockReference> mockReferences=new ArrayList<>();
    
    public Mock() {
    }


    
    



    




}
