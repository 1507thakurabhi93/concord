package com.walmartlabs.concord.it.keywhiz;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Wal-Mart Store, Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.google.common.base.Strings;

public final class ITConstants {

    public static final String SERVER_URL;

    static {
        SERVER_URL = "http://localhost:" + env("IT_SERVER_PORT", "8001");
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        if (Strings.isNullOrEmpty(v)) {
            return def;
        }
        return v;
    }

    private ITConstants() {
    }
}