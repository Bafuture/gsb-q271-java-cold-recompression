package com.example.gsb.recompress;

/** Storage tier of a record, decided by data age and access frequency. */
public enum Tier {
    /** Recently created or frequently accessed data; encoded with a fast codec. */
    HOT,
    /** Old and rarely accessed data; encoded with a high-ratio codec. */
    COLD
}
