// Local-dev accounts from .env.example; override for any other environment.
export const seller = {
  username: 'seller',
  password: process.env.E2E_SELLER_PASSWORD ?? 'seller-dev-password'
};

export const cs = {
  username: 'cs',
  password: process.env.E2E_CS_PASSWORD ?? 'cs-dev-password'
};
