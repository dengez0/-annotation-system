import os
import unittest
from unittest.mock import patch

from app import app


class WorkLogAccessTest(unittest.TestCase):
    def test_only_startup_ip_can_read_logs(self):
        app.config['TESTING'] = True
        with patch('services.admin_access.get_local_ipv4_addresses', return_value={'127.0.0.1'}), patch.dict(
            os.environ, {'SIMPLELABEL_ADMIN_IP': '192.168.77.109'}
        ):
            client = app.test_client()
            allowed = client.get('/api/work-logs/overview', environ_base={'REMOTE_ADDR': '192.168.77.109'})
            denied_page = client.get('/work-logs', environ_base={'REMOTE_ADDR': '192.168.77.110'})
            denied_api = client.get('/api/work-logs/overview', environ_base={'REMOTE_ADDR': '192.168.77.110'})

        self.assertEqual(allowed.status_code, 200)
        self.assertEqual(denied_page.status_code, 403)
        self.assertIn('无效访问', denied_page.get_data(as_text=True))
        self.assertEqual(denied_api.status_code, 403)

    def test_server_addresses_and_loopback_can_read_logs_without_env_configuration(self):
        app.config['TESTING'] = True
        with patch(
            'services.admin_access.get_local_ipv4_addresses',
            return_value={'127.0.0.1', '192.168.77.109'},
        ), patch.dict(os.environ, {'SIMPLELABEL_ADMIN_IP': ''}):
            client = app.test_client()
            lan_address = client.get(
                '/api/work-logs/overview', environ_base={'REMOTE_ADDR': '192.168.77.109'}
            )
            loopback = client.get(
                '/api/work-logs/overview', environ_base={'REMOTE_ADDR': '127.0.0.1'}
            )
            remote = client.get(
                '/api/work-logs/overview', environ_base={'REMOTE_ADDR': '192.168.77.110'}
            )

        self.assertEqual(lan_address.status_code, 200)
        self.assertEqual(loopback.status_code, 200)
        self.assertEqual(remote.status_code, 403)

    def test_invalid_calendar_date_returns_bad_request(self):
        app.config['TESTING'] = True
        with patch('services.admin_access.get_local_ipv4_addresses', return_value={'127.0.0.1'}), patch.dict(
            os.environ, {'SIMPLELABEL_ADMIN_IP': '192.168.77.109'}
        ):
            client = app.test_client()
            response = client.get(
                '/api/work-logs/overview?range=date&date=not-a-date',
                environ_base={'REMOTE_ADDR': '192.168.77.109'},
            )

        self.assertEqual(response.status_code, 400)


if __name__ == '__main__':
    unittest.main()
