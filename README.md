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




## Development
### Frontend
1) Ensure the app is created in routers when going throught the different views
### Backend
1)  Addition of dependencies for the project through pom.xml, configs are in applications .properties for the backend
2) Unit tests to be created for each features/functions when the time is right
### DB
1) All db tables to exist and created via migration files, do not auto create in the springboot, keep auto-ddl to validate.

