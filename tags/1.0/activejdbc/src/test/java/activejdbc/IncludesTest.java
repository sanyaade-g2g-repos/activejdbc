/*
Copyright 2009-2010 Igor Polevoy 

Licensed under the Apache License, Version 2.0 (the "License"); 
you may not use this file except in compliance with the License. 
You may obtain a copy of the License at 

http://www.apache.org/licenses/LICENSE-2.0 

Unless required by applicable law or agreed to in writing, software 
distributed under the License is distributed on an "AS IS" BASIS, 
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
See the License for the specific language governing permissions and 
limitations under the License. 
*/

package activejdbc;

import activejdbc.LazyList;
import activejdbc.test.ActiveJDBCTest;
import activejdbc.test_models.*;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * @author Igor Polevoy
 */
public class IncludesTest extends ActiveJDBCTest{

    @Test
    public void shouldBeAbleToIncludeParent() {
        resetTables("users", "addresses");
        List<Address> addresses = Address.findAll().orderBy("id").include(User.class);
        a(addresses.get(0).toMap().get("user")).shouldNotBeNull();
        Map user = (Map)addresses.get(0).toMap().get("user");
        a(user.get("first_name")).shouldBeEqual("Marilyn");

        user = (Map)addresses.get(6).toMap().get("user");
        a(user.get("first_name")).shouldBeEqual("John");
    }
    

    @Test
    public void shouldBeAbleToIncludeChildren() {
        resetTables("users", "addresses");
        LazyList<User> users = User.findAll().orderBy("id").include(Address.class);
        List<Map> maps = users.toMaps();

        Map user = maps.get(0);
        a(user.get("first_name")).shouldBeEqual("Marilyn");
        List<Map> addresses = (List<Map>)user.get("addresses");
        a(addresses.size()).shouldBeEqual(3);

        a(addresses.get(0).get("address1")).shouldBeEqual("123 Pine St.");
        a(addresses.get(1).get("address1")).shouldBeEqual("456 Brook St.");
        a(addresses.get(2).get("address1")).shouldBeEqual("23 Grove St.");
    }

    @Test
    public void shouldBeAbleToIncludeOtherInManyToMany() {
        resetTables("doctors", "patients", "doctors_patients");
        LazyList<Doctor> doctors = Doctor.findAll().orderBy("id").include(Patient.class);
        List<Map> doctorsMaps = doctors.toMaps();


        List<Map> patients = (List<Map>)doctorsMaps.get(0).get("patients");
        a(patients.size()).shouldBeEqual(2);

        patients = (List<Map>)doctorsMaps.get(1).get("patients");
        a(patients.size()).shouldBeEqual(1);
    }


    @Test
    public void shouldNotCallDependentIfOriginatedModelQueryReturnsNoResultsForManyToMany() {
        resetTables("doctors", "patients", "doctors_patients");

        LazyList<Patient> patients = Doctor.find("last_name = ?", " does not exist").include(Patient.class);

        a(patients.size()).shouldEqual(0);
        
    }

    @Test
    public void shouldNotCallDependentIfOriginatedModelQueryReturnsNoResultsForOneToMany() {
        resetTables("users", "addresses");

        LazyList<Address> addresses = User.find("email = ?", " does not exist").include(Address.class);

        a(addresses.size()).shouldEqual(0);
    }



    @Test
    public void shouldCacheIncludes() {
        resetTables("doctors", "patients", "doctors_patients");
        LazyList<Doctor> doctors = Doctor.findAll().orderBy("id").include(Patient.class);

        List<Patient> patients1 = doctors.get(0).getAll(Patient.class);
        List<Patient> patients2 = doctors.get(0).getAll(Patient.class);
        a(patients1).shouldBeTheSameAs(patients2);
    }

    @Test
    public void shouldBeAbleToIncludeParentAndChildren() {
        resetTables("libraries", "books", "readers");
        List<Book> books = Book.findAll().orderBy(Book.getMetaModel().getIdName()).include(Reader.class, Library.class);
        Map book = books.get(0).toMap();

        List<Map> readers = (List<Map>)book.get("readers");
        a(readers.get(0).get("last_name")).shouldBeEqual("Smith");
        a(readers.get(1).get("last_name")).shouldBeEqual("Doe");

        Map library = (Map)book.get("library");
        a(library.get("address")).shouldBeEqual("124 Pine Street");
    }
}
