package com.example.connect_sphere.mock.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.connect_sphere.mock.dto.MockDTO;
import com.example.connect_sphere.mock.entity.Mock;
import com.example.connect_sphere.mock.mapper.MockDtoMapper;
import com.example.connect_sphere.mock.repository.MockRepository;

/*Service layer where the main business logics lies in.Uses DI or autowired to inject repository in*/

@Service
public class MockService{
    private MockRepository mockRepository;
    private MockDtoMapper mockDtoMapper;
    public MockService(MockRepository mockRepository,MockDtoMapper mockDtoMapper){
	this.mockRepository=mockRepository;
	this.mockDtoMapper=mockDtoMapper;
    }
    /* Uses jpa repository's ORM like functions, dont need sql unless customised in the repository level*/
    public List<Mock> getAllMocks(){
	return mockRepository.findAll();


    }

    /* Note that findby id returns optional value, Mapper is being used to return specific values*/ 
    public MockDTO getMockById(UUID id){
	Mock mock=mockRepository.findById(id).orElseThrow(()->new RuntimeException());
	
	return mockDtoMapper.mocktoMockDTO(mock);


    }

    /* additional crud or biz related */






}

