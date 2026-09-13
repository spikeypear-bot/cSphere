## Project context for AI tools

Read **`AI_Context.md`** at the repository root before making any change with an AI coding assistant (Claude, Copilot, Cursor, etc.) — it's the single source of truth for scope, roles, architecture, conventions, and current status. `CLAUDE.md` is only a pointer to it, kept because Claude Code specifically looks for that exact filename.

## SetUp

1) Ensure Docker desktop is on.

### Frontend (React.ts)
from root:
2) npm run install:frontend / npm run ci:frontend
3) npm run dev

### Backend/DB
from root:
4) docker compose up --build -d (To build the springboot image and up the containers)
note: building of springboot app may take quite long during the first build, subsequently it takes 30s
5) docker compose down -d (To down containers)

### Database (PostgreSQL, paired with Springboot's Flyway migration)

note: all db files are to be written on the backend/src/main/resources/db/migration in the form of VERSION__description.sql, for example 1__init-table.sql 

### Tests

Frontend (Vitest + React Testing Library):
```
npm run test          # from frontend/, or npm --prefix frontend run test from root
npm run test:watch    # watch mode
```
docker container has been set up specifically to run jUNIT/mockito tests.

Mockito allows us to test layers with mock dependent layers, like servicetest class can have a @Mock repository field, so that I can just test the service layer by itself. Junit makes use of the AssertJ and @Test(marking a method as test for junit) to check the different testcases and assert a response on the service, like could be asserting errors or asserting the values of the service function. 

Controller makes use of @WebMvcTest + @Mockitobean (register the mockito fake in spring container) tests for container layer, makes use of mockmvc to act as client and mockito bean for the mock service

Springboot test test for the application with the DB connection mainly to test if it can be compiled, note that lazy initialisation is set to false so that every thing is loaded and test. 

commands to run :

run all tests:
1. docker compose run --rm backend-test 

run a single test:
2. docker compose run --rm test -Dtest=EventRequestServiceTest

run a single method:
3. docker compose run --rm backend-test test  -Dtest='EventRequestServiceTest#methodName'

runing test without DB:
4. docker compose run --rm --no-deps backend-test test -Dtest=EventRequestServiceTest

clean+all tests:
5. docker compose run --rm backend-test clean test


./mvnw test            # from backend/, if you have JDK 25 locally
```

## Development
### Frontend
1) Ensure the app is created in routers when going throught the different views
2) Shared UI primitives live in `frontend/src/components/ui/`; per-role screens live under `frontend/src/features/<role>/`
3) There is no real login yet — see `docs/decision-log.md` D6a: the main page is a "Login as [Role]" selector (`frontend/src/lib/session.tsx`)
### Backend
1)  Addition of dependencies for the project through pom.xml, configs are in applications .properties for the backend
2) Unit tests to be created for each features/functions when the time is right
### DB
1) All db tables to exist and created via migration files, do not auto create in the springboot, keep auto-ddl to validate.
