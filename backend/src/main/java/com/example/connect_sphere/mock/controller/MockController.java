package com.example.connect_sphere.mock.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.connect_sphere.mock.dto.MockDTO;
import com.example.connect_sphere.mock.service.MockService;



/* Controller layer, can used whatever type of controller u want, used as routing for the api, injects service layer in */
@RestController
public class MockController{
    private MockService mockService;
    public MockController(MockService mockService){
	this.mockService=mockService;


    }

    /* use of get,post put,delete mappings , request param,body param to define where to get the inputs from , can consider the use of responsebody with status code and the field inside then define the status code upon errors  and return somethings */
    @GetMapping("/api/mock")
    public MockDTO getMock(@RequestParam UUID id){
	return mockService.getMockById(id);

    

    } 

    




}

