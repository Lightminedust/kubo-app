package com.kubo.model.apps;

/** What keeps an application running. */
public enum Manager {
    /** PM2: Node applications, one process or a cluster of them, per user. */
    PM2("pm2"),
    /** supervisord: any program, described in /etc/supervisor, read through supervisorctl. */
    SUPERVISOR("supervisor");

    private final String word;

    Manager(String word) {
        this.word = word;
    }

    public String word() {
        return word;
    }
}
