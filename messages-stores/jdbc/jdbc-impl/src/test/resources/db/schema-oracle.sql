
    drop table fix_messages cascade constraints;

    drop table fix_messages_session_state cascade constraints;

    create table fix_messages (
        sequence_number number(19,0) not null,
        session_id varchar2(255 char) not null,
        message_data blob not null,
        primary key (sequence_number, session_id)
    );

    create table fix_messages_session_state (
        session_id varchar2(255 char) not null,
        incoming_seq_num number(19,0) not null,
        outgoing_seq_num number(19,0) not null,
        primary key (session_id)
    );

    create index idx_session_seq 
       on fix_messages (session_id, sequence_number);

    create index idx_session 
       on fix_messages_session_state (session_id);
