Zebrunner Device Farm - Selenium Hub
==================

## Support Zebrunner CE
Feel free to support the development with a [**donation**](https://www.paypal.com/donate?hosted_button_id=JLQ4U468TWQPS) for the next improvements.

<p align="center">
  <a href="https://zebrunner.com/"><img alt="Zebrunner" src="https://github.com/zebrunner/zebrunner/raw/master/docs/img/zebrunner_intro.png"></a>
</p>

## Usage

### Build steps
> Follow the installation and configuration guide in [MCloud](https://github.com/zebrunner/mcloud) to reuse this image effectively.

```
mvn -U clean compile assembly:single package
docker build . -t zebrunner/mcloud-grid:latest
```

### Run MCloud Grid
```
docker run -d -p 4444:4444 -e GRID_NEW_SESSION_WAIT_TIMEOUT=240000 \
		-e GRID_TIMEOUT=60000 -e GRID_BROWSER_TIMEOUT=60000 \
		--name mcloud-grid zebrunner/mcloud-grid:latest
```

### Env vars list for STF
```
AUTHKEY
SECRET
RETHINKDB_PORT_28015_TCP
STF_URL
STF_TOKEN
STF_ROOT_GROUP_NAME
STF_ADMIN_NAME
STF_ADMIN_EMAIL
```

## Documentation and free support
* [Zebrunner PRO](https://zebrunner.com)
* [Zebrunner CE](https://zebrunner.github.io/community-edition)
* [Zebrunner Reporting](https://zebrunner.com/documentation)
* [Carina Guide](http://zebrunner.github.io/carina)
* [Demo Project](https://github.com/zebrunner/carina-demo)
* [Telegram Channel](https://t.me/zebrunner)
