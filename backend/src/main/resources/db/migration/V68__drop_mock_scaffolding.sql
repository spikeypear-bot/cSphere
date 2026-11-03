-- V1 created mock and mock_references for a demo slice (backend mock package)
-- that showed the MVC layering before any real entity existed. Real slices
-- now show the convention and the package is gone, so its tables go too.
-- V1 itself stays: applied migrations are never edited.
DROP TABLE IF EXISTS mock_references;
DROP TABLE IF EXISTS mock;
