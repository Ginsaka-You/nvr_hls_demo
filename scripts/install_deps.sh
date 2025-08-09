#!/usr/bin/env bash
set -e
sudo apt-get update
sudo apt-get install -y ffmpeg nginx openjdk-11-jdk maven
sudo mkdir -p /var/www/streams
sudo chown -R www-data:www-data /var/www/streams
echo "Done. Remember to place nginx snippet and restart nginx."
