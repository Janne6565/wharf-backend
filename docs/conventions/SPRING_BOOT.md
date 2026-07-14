<!-- AUTO-SYNCED from agents KB: technologies/SPRING_BOOT.md @ 4fe753e.
     Do NOT edit here — edit the source in ~/projects/agents and re-run scripts/sync-conventions.sh. -->

# Spring Boot Rules

## Layered Architecture

**DO:**
- Keep controllers thin: validate input, delegate to service, return response.
- Put all business logic in the service layer.
- Put all data access in the repository layer.
- Keep entities free of business logic and DTO conversion.
- Define a controller interface (the "schema") that holds all REST-specific annotations: `@RequestMapping`, `@Operation`, `@ApiResponse`, `@Tag`, parameter annotations, etc. The controller class implements this interface and contains only the method bodies.

  ```java
  // WorkAssignmentApi.java — the schema interface
  @RequestMapping("/api/v1/assignments")
  @Tag(name = "Work Assignments")
  public interface WorkAssignmentApi {

      @GetMapping("/{id}")
      @Operation(summary = "Get assignment by ID")
      @ApiResponse(responseCode = "200", description = "Assignment found")
      @ApiResponse(responseCode = "404", description = "Not found")
      ResponseEntity<AssignmentDto> getById(@PathVariable UUID id);
  }

  // WorkAssignmentController.java — the implementation
  @RestController
  public class WorkAssignmentController implements WorkAssignmentApi {

      private final AssignmentService service;

      @Override
      public ResponseEntity<AssignmentDto> getById(UUID id) {
          return ResponseEntity.ok(service.getById(id));
      }
  }
  ```

  This keeps the controller class readable, separates the API contract from the implementation, and makes the OpenAPI annotations easy to find and maintain.

**DON'T:**
- Call repositories directly from controllers.
- Put `toDto()` or mapping logic inside entity classes — this breaks SRP.
- Leak JPA entities out of the service layer into REST responses.
- Put `@Operation`, `@ApiResponse`, or other OpenAPI annotations directly on the controller class — put them on the interface instead.

---

## Package Structure

Organise the codebase **by component type, not by feature**. All controllers live together, all entities live together, and so on. A single feature is therefore spread across the type packages rather than being confined to one feature package.

```
com.example.app
├── Application                   # @SpringBootApplication (root — drives component scan)
├── client/                       # outbound integrations (external API ports + impls)
├── configuration/                # @Configuration + @ConfigurationProperties
├── controller/
│   ├── GlobalExceptionHandler    # @RestControllerAdvice
│   └── v1/
│       ├── schema/               # *Api interfaces — REST/OpenAPI annotations only
│       └── implementation/       # *Controller classes — implement the schema, method bodies only
├── entity/                       # JPA @Entity classes
├── model/
│   ├── action/                   # request DTOs (input)
│   ├── core/                     # response DTOs (output) + shared enums
│   ├── admin/                    # admin-only response DTOs (never exposed to regular users)
│   └── exception/                # BaseException + all domain exceptions
├── repository/                   # Spring Data repositories
├── security/                     # SecurityFilterChain, JWT/authorization components
└── services/                     # business logic, sub-packaged by domain as it grows
```

**DO:**
- Place a new class in the package for its **type** (controller, entity, repository, service, …), not its feature.
- Split every endpoint into `controller/v1/schema` (the `*Api` interface) and `controller/v1/implementation` (the `*Controller`), per Layered Architecture above.
- Separate DTOs by direction and audience: inputs in `model/action`, outputs in `model/core`, admin-only outputs in `model/admin`.
- Keep `@SpringBootApplication` at the root package so component scanning covers every type package automatically.
- Sub-package `services/` by domain (e.g. `auth`, `notification`, `core`) as it grows, rather than creating a package per feature.

**DON'T:**
- Introduce feature packages that mix controllers, services, entities, and DTOs together.
- Put request and response DTOs in the same package — keep the `action` / `core` split.
- Expose entities (from `entity/`) directly in responses — always map to a `model/` DTO.

---

## Transaction Management

**DO:**
- Annotate every service method that writes to the database with `@Transactional`.
- Use `@Transactional(readOnly = true)` on read-only service methods to allow DB-level optimisations.
- Use `@Transactional(propagation = Propagation.MANDATORY)` on repository methods that must always be called within an existing transaction.
- Use `@Version` on entities for optimistic locking when concurrent updates are possible.
- Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` with a `findAndLock*` naming convention when you need to serialize access to a row.

**DON'T:**
- Leave multi-step write operations without `@Transactional` — partial commits cause data integrity bugs.
- Call `@Transactional` methods from within the same class (self-invocation bypasses the proxy).
- Use `REQUIRES_NEW` without understanding it suspends the outer transaction.

---

## Database & JPA

**DO:**
- Use Flyway for all schema changes — no DDL in code or application properties.
- Use the JPA Metamodel (generated `_` classes) for type-safe Specifications — no magic string field names.
- Name lock methods explicitly: `findAndLockById(...)`, `findAndLockByKey(...)`.
- Use `Instant` for all timestamps (`java.time.Instant`).

**DON'T:**
- Use `new java.util.Date()` — use `Instant.now()`.
- Use `spring.jpa.hibernate.ddl-auto=update` in any non-local environment.
- Write JPQL or native queries with hardcoded column/field name strings when a Specification or metamodel alternative exists.

---

## Configuration Properties

**DO:**
- Use `@ConfigurationProperties(prefix = "...")` with a validated, nested POJO for all app-specific config.
- Annotate config classes with `@Validated` and use Bean Validation (`@NotNull`, `@Min`, etc.) on fields.
- Bind config once at startup — fail fast if required properties are missing.

**DON'T:**
- Use `@Value` for anything beyond trivial single values — it does not compose or validate.
- Scatter config reads across multiple classes without a central properties class.

---

## Error Handling

**DO:**
- Define a `BaseException` that carries the HTTP status code and response detail. All custom application exceptions extend it.
- Name exceptions after the domain concept and failure reason: `UserNotFoundException`, `AssignmentAlreadyClosedException`, etc.
- Use a single `@ExceptionHandler(BaseException.class)` in your `@RestControllerAdvice` to handle all custom exceptions — no new handler method needed per exception type.

  ```java
  // BaseException.java
  public abstract class BaseException extends RuntimeException {
      private final HttpStatus status;
      private final String detail;

      protected BaseException(HttpStatus status, String detail) {
          super(detail);
          this.status = status;
          this.detail = detail;
      }

      public HttpStatus getStatus() { return status; }
      public String getDetail() { return detail; }
  }

  // UserNotFoundException.java
  public class UserNotFoundException extends BaseException {
      public UserNotFoundException(UUID userId) {
          super(HttpStatus.NOT_FOUND, "User not found: " + userId);
      }
  }

  // GlobalExceptionHandler.java
  @RestControllerAdvice
  public class GlobalExceptionHandler {

      @ExceptionHandler(BaseException.class)
      public ProblemDetail handle(BaseException ex) {
          ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getDetail());
          // log based on status category
          return problem;
      }
  }
  ```

- Return RFC 7807 Problem Detail responses (`application/problem+json`) for all error responses.
- Use a dedicated named logger per HTTP status code category for structured filtering (e.g. `log4xx`, `log5xx`).

**DON'T:**
- Add a new `@ExceptionHandler` method for every exception type — extend `BaseException` instead.
- Catch and swallow exceptions without logging or rethrowing.
- Return stack traces or internal messages in error responses sent to clients.
- Handle the same exception type in multiple places.

---

## Logging

**DO:**
- Log at `DEBUG` for the happy path (successful operations, normal flow).
- Log at `WARN` for recoverable client errors (4xx) — the client did something wrong, not the service.
- Log at `ERROR` for server errors (5xx) — something the service is responsible for.
- Include enough context in log messages to diagnose the issue without a debugger (IDs, statuses, key values).
- Use SLF4J with parameterised messages: `log.debug("Processing item {}", id)` — never string concatenation.

**DON'T:**
- Log at `WARN` or `ERROR` for expected 4xx responses — this creates noise in alerting.
- Log sensitive data (PII, tokens, passwords).
- Use `System.out.println` or `printStackTrace()`.

---

## Validation

**DO:**
- Validate all incoming request bodies and path/query parameters with Bean Validation (`@Valid`, `@NotNull`, `@Size`, etc.) at the controller layer.
- Validate configuration properties at startup with `@Validated`.
- Use custom `ConstraintValidator` implementations for domain-specific rules.

**DON'T:**
- Duplicate validation logic across the controller and service layer.
- Perform validation inside entity setters — validate at the boundary.

---

## Security

**DO:**
- Define security rules in a dedicated `SecurityFilterChain` bean.
- Use method-level security (`@PreAuthorize`) for fine-grained access control on service methods.
- Validate and parse JWTs in a dedicated filter or component — not inside business logic.
- Externalize allowed origins, token issuers, and other security config via `@ConfigurationProperties`.

**DON'T:**
- Hardcode roles, issuers, or secrets in source code.
- Disable CSRF without explicitly documenting why (e.g. stateless JWT API).
- Mix authentication logic with business logic.

---

## Testing Conventions

**DO:**
- Use JUnit 5 (`@ExtendWith(MockitoExtension.class)`) and AssertJ for assertions.
- Prefer `assertThatThrownBy(...)` over `@Test(expected = ...)` for exception assertions.
- Use `.satisfies(...)` in AssertJ to group assertions on a single object cleanly.
- Write tests for failure paths and boundary values, not just the happy path.
- Use `@SpringBootTest` sparingly — prefer unit tests with mocks for service logic.
- Use `@DataJpaTest` for repository-layer tests with an in-memory DB.

**DON'T:**
- Assert only that no exception was thrown — assert the actual result.
- Use `Mockito.when(...).thenReturn(...)` for behaviour that should be tested for real (e.g. DB queries).
- Write tests that only cover the happy path and skip error branches.

---

## Code Style

**DO:**
- Use explicit imports — no wildcard imports (`import java.util.*`).
- Keep methods short and focused on a single responsibility.
- Use Spring profiles (`@Profile`) as feature flags for environment-specific behaviour.
- Define profile name constants in a dedicated `Profiles` class rather than using raw strings.
- Use Lombok (`@Getter`, `@Builder`, `@RequiredArgsConstructor`) to reduce boilerplate, but prefer explicit constructors when clarity matters.

**DON'T:**
- Use magic numbers or strings — extract them as named constants.
- Suppress warnings (`@SuppressWarnings`) without a comment explaining why.
- Mix concerns in a single class (e.g. a service that also handles HTTP response formatting).
