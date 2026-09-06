package com.example.connect_sphere.mock.mapper;

import org.mapstruct.Mapper;

import com.example.connect_sphere.mock.dto.MockDTO;
import com.example.connect_sphere.mock.entity.Mock;

@Mapper(componentModel = "spring")
public interface MockDtoMapper{
    MockDTO mocktoMockDTO (Mock entity);
    Mock mockDTOtoMock (MockDTO dto);
     


}


