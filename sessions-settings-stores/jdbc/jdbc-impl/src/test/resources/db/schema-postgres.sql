
    set client_min_messages = WARNING;

    drop table if exists fix_sessions_settings cascade;

    create table fix_sessions_settings (
        engine_id varchar(255) not null,
        qualified_name varchar(255) not null,
        session_type varchar(16) not null,
        settings text not null,
        updated_at timestamp(6) with time zone not null,
        version bigint not null,
        primary key (engine_id, qualified_name)
    );
