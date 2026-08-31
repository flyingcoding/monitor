package org.monitorclient.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConnectionConfig {
    String address;
    @lombok.ToString.Exclude
    String token;
}
