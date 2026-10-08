// Configura o replica set de 3 nós (rodado pelo serviço mongo-init; idempotente, pode rodar em todo deploy).
// Instalação nova: inicia com os 3 membros. Base existente (ex.: o antigo nó único com os dados carregados):
// acrescenta os membros que faltam, que copiam os dados do primário (initial sync), e dá prioridade ao nó "mongo".
const hosts = ['mongo:27017', 'mongo2:27017', 'mongo3:27017'];

function configured() {
  try {
    rs.status();
    return true;
  } catch (e) {
    return false;
  }
}

function waitForPrimary() {
  for (let i = 0; i < 120 && !db.hello().isWritablePrimary; i++) {
    sleep(1000);
  }
  if (!db.hello().isWritablePrimary) {
    throw new Error('node "mongo" did not become primary');
  }
}

if (!configured()) {
  rs.initiate({
    _id: 'rs0',
    members: hosts.map((host, i) => ({ _id: i, host, priority: i === 0 ? 2 : 1 })),
  });
  waitForPrimary();
  print('replica set initiated with ' + hosts.length + ' members');
} else {
  waitForPrimary();
  for (const host of hosts.slice(1)) {
    if (!rs.conf().members.some((m) => m.host === host)) {
      rs.add({ host, priority: 1 });
      print('added ' + host + ' (initial sync starts now)');
      sleep(2000);
    }
  }
  const config = rs.conf();
  const first = config.members.find((m) => m.host === hosts[0]);
  if (first && first.priority !== 2) {
    first.priority = 2;
    rs.reconfig(config);
    print('priority 2 for ' + hosts[0]);
  }
}
print(rs.status().members.map((m) => m.name + '=' + m.stateStr).join(', '));
