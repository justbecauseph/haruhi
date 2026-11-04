-- Per-character dialogue flags. Clearing a flag deletes its row, so an absent row reads as unset.
CREATE TABLE flags (
    character_id uuid        NOT NULL,
    namespace    text        NOT NULL,
    flag         text        NOT NULL,
    value        int         NOT NULL,
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (character_id, namespace, flag)
);

-- How many times a character opened a graph with an NPC, and when they last did.
CREATE TABLE visits (
    character_id uuid        NOT NULL,
    graph        text        NOT NULL,
    npc          text        NOT NULL,
    count        int         NOT NULL,
    last_visit   timestamptz NOT NULL,
    PRIMARY KEY (character_id, graph, npc)
);

-- Immutable content revisions. activated_at keeps the time of the latest activation after the revision is replaced.
CREATE TABLE content_revision (
    id           bigserial   PRIMARY KEY,
    digest       text        NOT NULL UNIQUE,
    bundle       text        NOT NULL,
    author       text        NOT NULL,
    created_at   timestamptz NOT NULL,
    activated_at timestamptz,
    activated_by text,
    active       boolean     NOT NULL DEFAULT false
);

-- Every active row has the same key, so a second active row violates this index.
CREATE UNIQUE INDEX content_revision_one_active ON content_revision (active) WHERE active;
