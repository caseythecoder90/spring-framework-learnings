-- One table, one column of interest. The subject is the transaction, not the schema.
create table item (
    id    varchar(64) primary key,
    label varchar(64) not null
);
