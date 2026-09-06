package com.example.connect_sphere.mock.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.connect_sphere.mock.entity.Mock;

/* this is the repository, used to communicated with the db, where sql queries are stores extends jpa repository (which includes paging etc, refer to jpa repository for info)*/
@Repository
public interface MockRepository extends JpaRepository<Mock,UUID>{


     





}
