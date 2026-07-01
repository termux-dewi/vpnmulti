#!/usr/bin/env python3
"""
Termux Engine Mockup for Tailscale Virtual Interface (ts0)
Connects to the Android app's Abstract Unix Domain Socket,
parses the raw Layer 3 IP packet stream, and echoes packets back
by swapping the source and destination addresses/ports (loopback response).
"""

import socket
import sys
import struct
import time

UDS_PATH = "\0uds_interface_ts0"  # Abstract namespace socket name (\0 denotes abstract)

def parse_ip_packet(packet):
    """
    Parses a raw IP packet (IPv4 or IPv6).
    Returns a dictionary of extracted metadata, or None if invalid.
    """
    if len(packet) < 20:
        return None

    version = (packet[0] >> 4) & 0x0F
    
    if version == 4:
        ihl = (packet[0] & 0x0F) * 4
        if len(packet) < ihl:
            return None
        
        # Unpack IPv4 Header fields
        total_length = struct.unpack("!H", packet[2:4])[0]
        protocol = packet[9]
        src_ip = socket.inet_ntoa(packet[12:16])
        dst_ip = socket.inet_ntoa(packet[16:20])
        
        src_port = 0
        dst_port = 0
        payload_offset = ihl
        
        # Check if Protocol is TCP (6) or UDP (17) to parse ports
        if protocol in (6, 17) and len(packet) >= ihl + 4:
            src_port = struct.unpack("!H", packet[ihl:ihl+2])[0]
            dst_port = struct.unpack("!H", packet[ihl+2:ihl+4])[0]
            payload_offset += 8
            
        return {
            "version": 4,
            "header_len": ihl,
            "total_len": total_length,
            "protocol": protocol,
            "src_ip": src_ip,
            "dst_ip": dst_ip,
            "src_port": src_port,
            "dst_port": dst_port,
            "payload_len": total_length - payload_offset
        }
        
    elif version == 6:
        if len(packet) < 40:
            return None
        
        # Unpack IPv6 Header fields
        payload_len = struct.unpack("!H", packet[4:6])[0]
        next_header = packet[6]
        src_ip = socket.inet_ntop(socket.AF_INET6, packet[8:24])
        dst_ip = socket.inet_ntop(socket.AF_INET6, packet[24:40])
        
        src_port = 0
        dst_port = 0
        payload_offset = 40
        
        if next_header in (6, 17) and len(packet) >= 44:
            src_port = struct.unpack("!H", packet[40:42])[0]
            dst_port = struct.unpack("!H", packet[42:44])[0]
            payload_offset += 8
            
        return {
            "version": 6,
            "header_len": 40,
            "total_len": payload_len + 40,
            "protocol": next_header,
            "src_ip": src_ip,
            "dst_ip": dst_ip,
            "src_port": src_port,
            "dst_port": dst_port,
            "payload_len": payload_len
        }
        
    return None

def read_exactly(sock, num_bytes):
    """Reads exactly num_bytes from stream socket or returns empty if EOF."""
    data = b""
    while len(data) < num_bytes:
        chunk = sock.recv(num_bytes - len(data))
        if not chunk:
            return b""
        data += chunk
    return data

def build_loopback_reply(packet, info):
    """
    Swaps Source/Destination IPs and ports to generate a loopback reply packet.
    """
    reply = bytearray(packet)
    
    if info["version"] == 4:
        # Swap source and destination IP addresses (Offsets 12-15 and 16-19)
        reply[12:16], reply[16:20] = reply[16:20], reply[12:16]
        
        # Swap TCP/UDP ports if applicable (IHL is the start of transport header)
        ihl = info["header_len"]
        if info["protocol"] in (6, 17):
            reply[ihl:ihl+2], reply[ihl+2:ihl+4] = reply[ihl+2:ihl+4], reply[ihl:ihl+2]
            
        # Optional: Reset Checksum to 0 so the OS or local stack recalculates it
        reply[10:12] = b"\x00\x00"
        
    elif info["version"] == 6:
        # Swap source and destination IPv6 addresses (Offsets 8-23 and 24-39)
        reply[8:24], reply[24:40] = reply[24:40], reply[8:24]
        
        # Swap TCP/UDP ports if applicable
        if info["protocol"] in (6, 17):
            reply[40:42], reply[42:44] = reply[42:44], reply[40:42]
            
    return bytes(reply)

def main():
    print("=====================================================")
    print("      NetMux: Tailscale Engine Emulation Server      ")
    print("=====================================================")
    print(f"Connecting to abstract socket: {UDS_PATH.replace(chr(0), '\\0')}")

    while True:
        try:
            # Create a Unix Domain Socket (Stream type)
            sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            sock.connect(UDS_PATH)
            print("[+] Connected to NetMux VpnService successfully!")
            
            while True:
                # 1. Read first byte to determine IP version
                first_byte_data = read_exactly(sock, 1)
                if not first_byte_data:
                    break
                
                first_byte = first_byte_data[0]
                version = (first_byte >> 4) & 0x0F
                
                packet_data = b""
                if version == 4:
                    # Read remaining 19 bytes of minimal IPv4 header
                    header_rest = read_exactly(sock, 19)
                    if not header_rest:
                        break
                    
                    full_header = first_byte_data + header_rest
                    total_length = struct.unpack("!H", full_header[2:4])[0]
                    
                    # Read remaining payload bytes
                    payload_len = total_length - 20
                    payload_data = b""
                    if payload_len > 0:
                        payload_data = read_exactly(sock, payload_len)
                        if not payload_data:
                            break
                    packet_data = full_header + payload_data
                    
                elif version == 6:
                    # Read remaining 39 bytes of fixed IPv6 header
                    header_rest = read_exactly(sock, 39)
                    if not header_rest:
                        break
                    
                    full_header = first_byte_data + header_rest
                    payload_length = struct.unpack("!H", full_header[4:6])[0]
                    
                    # Read remaining payload bytes
                    payload_data = read_exactly(sock, payload_length)
                    if not payload_data:
                        break
                    packet_data = full_header + payload_data
                else:
                    print(f"[-] Received invalid IP version: {version}. Discarding frame...")
                    continue
                
                # Parse and log the packet
                info = parse_ip_packet(packet_data)
                if info:
                    proto_name = "TCP" if info["protocol"] == 6 else "UDP" if info["protocol"] == 17 else f"PROTO:{info['protocol']}"
                    print(f"[TS0 OUTBOUND] Proto: {proto_name} | {info['src_ip']}:{info['src_port']} -> {info['dst_ip']}:{info['dst_port']} | Len: {info['total_len']} bytes (Payload: {info['payload_len']}b)")
                    
                    # Auto-generate a Loopback Echo Reply packet
                    reply_packet = build_loopback_reply(packet_data, info)
                    
                    # Write the loopback response back to UDS
                    sock.sendall(reply_packet)
                    print(f"[TS0 INBOUND] Echoed packet back to Android TUN -> {info['dst_ip']}:{info['dst_port']} -> {info['src_ip']}:{info['src_port']}")
            
        except socket.error as e:
            print(f"[-] Connection state lost: {e}. Retrying in 3 seconds...")
            time.sleep(3)
        except KeyboardInterrupt:
            print("\n[!] Exiting Tailscale engine emulator.")
            sys.exit(0)
        finally:
            sock.close()

if __name__ == "__main__":
    main()
