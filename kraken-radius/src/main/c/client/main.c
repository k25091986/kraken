/*
 * Copyright 2011 Future Systems
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <stdio.h>
#include <stdlib.h>
#include "radius.h"

int main(int argc, char *argv[]) 
{
	int i = 0;
	int ret = 0;
	radius_client_t *client = NULL;
	byte_t *encoded = NULL;
	int encoded_len = 0;
	radius_packet_t *response = NULL;
	byte_t authenticator[] = { 0x34, 0x65, 0xa0, 0x8e, 0x9e, 0x7a, 0x9c, 0xb1, 0x50, 0x42, 0x25, 0x28, 0xb0, 0x83, 0x40, 0x86 };
	
#ifdef WIN32
	WSADATA wsa;
	ret = WSAStartup(MAKEWORD(2, 2), &wsa);
#endif // WIN32
	
	ret = radius_client_new( "172.20.2.2", RADIUS_AUTH_PORT, "10testing", &client );
	ret = radius_client_papauth( client, "xeraph", "qooguevara", &response );
	printf( "result: %x, code: %d \n", ret, response->code );

	radius_packet_free( response );
	radius_client_free( client );
	
	return 0;
}