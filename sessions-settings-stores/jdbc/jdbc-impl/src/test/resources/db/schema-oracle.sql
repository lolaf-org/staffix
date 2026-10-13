
    drop table fix_sessions_settings cascade constraints;

    create table fix_sessions_settings (
        engine_id varchar2(255 char) not null,
        qualified_name varchar2(255 char) not null,
        session_type varchar2(16 char) not null,
        settings clob not null,
        updated_at timestamp(9) with time zone not null,
        version number(19,0) not null,
        primary key (engine_id, qualified_name)
    );
