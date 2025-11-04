# Example Web API built with Elemental and Micronaut

[![Java 25](https://img.shields.io/badge/java-25-blue.svg)](https://adoptopenjdk.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

Built for a workshop given by Adam Retter at Declarative Amsterdam 2025.

Open API Specification for the Web API is here: https://app.swaggerhub.com/apis/evolvedbinary/dasp-xforms-api/1.0.0

## Compiling

```shell
$ ./mvnw clean compile
```

## Running (Dev mode)
```shell
$ ./mvnw mn:run
```

**NOTE**: If you are on a Windows system, replace `./mvnw` with `mvnw`

The WebAPI will then be available beneath: http://localhost:9090
