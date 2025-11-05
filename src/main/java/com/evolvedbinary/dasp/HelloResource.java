package com.evolvedbinary.dasp;

import jakarta.ws.rs.*;

@Path("/hello")
public class HelloResource {

    @GET
    public String createDocument() {
        return "Hello DA 2025";
    }
}
