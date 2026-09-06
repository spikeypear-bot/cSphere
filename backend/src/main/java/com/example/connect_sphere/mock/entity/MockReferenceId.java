package com.example.connect_sphere.mock.entity;

import java.io.Serializable;
import java.util.UUID;
/* Defining the id for the mockreference composite key, needs to implement serializable, need to override equals and hashcode, note that mock in this case is UUID */
public class MockReferenceId implements Serializable{
    private UUID mock;
    private String mockString;

    
    public MockReferenceId(){}
    public MockReferenceId(UUID mock,String mockString){
	this.mock=mock;
	this.mockString=mockString;
    }
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((mock == null) ? 0 : mock.hashCode());
		result = prime * result + ((mockString == null) ? 0 : mockString.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		MockReferenceId other = (MockReferenceId) obj;
		if (mock == null) {
			if (other.mock != null)
				return false;
		} else if (!mock.equals(other.mock))
			return false;
		if (mockString == null) {
			if (other.mockString != null)
				return false;
		} else if (!mockString.equals(other.mockString))
			return false;
		return true;
	}
         



}
