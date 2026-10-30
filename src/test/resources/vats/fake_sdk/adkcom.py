# A stand-in for Verifone's VATS SDK, from the Python CAT's tests/unit/vats_fake_sdk.py.

class AdkCom:
    class COM_INTERFACE:
        ETH0 = 1
    @staticmethod
    def get_network_interface_info(interface):
        return {"IP_Addr": "192.0.2.10", "DHCP": True}
