import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

export function uniqueSku(existing: (sku: string) => boolean): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;
    if (!value) {
      return null;
    }
    return existing(value) ? { uniqueSku: true } : null;
  };
}