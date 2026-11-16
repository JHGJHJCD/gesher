// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

import java.util.List;
import java.util.Map;

public abstract class HeaderBlock {
    public abstract List<Map.Entry<String,String>> getAsList();
    public abstract Map<String,List<String>> getAsMap();
}
