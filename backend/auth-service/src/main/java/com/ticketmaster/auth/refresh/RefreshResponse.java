package com.ticketmaster.auth.refresh;

/**
 * No user object, unlike login: the client already knows who it is, and
 * re-sending the profile on a call that happens every ten minutes is waste.
 * The replacement refresh token is absent for the same reason it is absent
 * from the login response - it travels as an httpOnly cookie.
 */
record RefreshResponse(String accessToken, String tokenType, long expiresIn) {
}
