set
client_min_messages = WARNING;

drop table if exists fix_messages cascade;

drop table if exists fix_messages_session_state cascade;

create table fix_messages
(
    sequence_number bigint       not null,
    session_id      varchar(255) not null,
    message_data    bytea        not null,
    primary key (sequence_number, session_id)
);

create table fix_messages_session_state
(
    session_id       varchar(255) not null,
    incoming_seq_num bigint       not null,
    outgoing_seq_num bigint       not null,
    primary key (session_id)
);

create index idx_session_seq
    on fix_messages (session_id, sequence_number);

create index idx_session
    on fix_messages_session_state (session_id);
